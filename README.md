# wallet-ledger: one account balance, four ways of keeping it correct under load

A small payments service that holds account balances and moves money between them. It exists to
answer one question properly: when two requests touch the same account at the same time, what
stops them from writing over each other, and what does each answer cost you.

There are four implementations of the same five operations behind one interface. One of them is
wrong, and a test in this repository runs it until the total is wrong and then measures by how much. The
other three are correct and they are correct for different reasons, at different prices.

## Running it

```bash
docker compose up
```

That starts Postgres, Redis and the service on port 8080. Then:

```bash
# open an account with 100.00 in it, in pence
curl -s -X POST localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"openingBalance": 10000}'

# move 25.00 to another account
curl -s -X POST localhost:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 4f1c0b1e-8f5a-4c9a-9d2e-1b7a55e4c2d1' \
  -d '{"from": "<first id>", "to": "<second id>", "amount": 2500}'
```

The tests need Docker and nothing else. They start their own Postgres and Redis.

```bash
./gradlew test
```

**69 tests across 12 test classes**, all passing, on Java 21.0.4. The concurrency tests print what
they measured while they run.

## The invariant

Two numbers per account, not one.

```
available   everything the account owns
locked      the part of it that is already promised to something else
```

What the holder can actually spend is the difference. One number cannot hold both answers: an
account with 1,000 in it and 900 of that committed to a pending order has 1,000 and can spend 100,
and a balance that collapses those into a single figure has to be wrong about one of them.

The rule is `0 <= locked <= available`, and every operation leaves it true except one, which is
covered further down.

| Operation | What it does |
|---|---|
| `deposit(account, amount)` | `available += amount` |
| `reserve(account, amount)` | promise an amount. `locked += amount`, if there is enough spendable |
| `release(account, amount)` | drop a promise. `locked -= amount` |
| `settle(account, released, settled)` | act on a promise. `locked -= released`, `available -= settled` |
| `transfer(from, to, amount)` | `from.available -= amount`, `to.available += amount` |

Money is `BIGINT` minor units everywhere, so 1250 is twelve pounds fifty. There is no decimal or
floating point column in the schema. A balance reduced by successive subtractions and a remainder
computed in one go differ in the last bit once floats are involved, and a ledger cannot be unsure
whether those two numbers are the same.

Amounts are added with `Math.addExact`, so a balance that would not fit in a signed 64 bit integer
throws instead of wrapping round into a large negative number.

## The race

Here is the implementation almost everybody writes first. It is in
[`UnguardedLedgerService`](src/main/java/com/iliyanadov/walletledger/ledger/UnguardedLedgerService.java).

```
read the payer's balance
read the payee's balance
can the payer afford it?
write the payer's new balance
write the payee's new balance
```

On one thread it is correct, and `LedgerSemanticsIT` runs the same suite of rules against it as
against the three that work, and it passes all of them.

The defect is the gap between the reads at the top and the writes at the bottom. Two callers can
both read the same balances, both decide the transfer is affordable, and both write a number they
worked out from a balance that is no longer true. The second write erases the first. Neither
caller sees an error, because from inside either one, nothing went wrong.

Worse than a lost transfer is an inconsistent read. The payer's balance and the payee's balance
are read separately, so a caller can read the payer before another transfer commits and the payee
after it. It then writes both numbers from a pair that never existed at the same moment, and the
total across the two accounts changes. Money is created.

**[`UnguardedTransferRaceIT`](src/test/java/com/iliyanadov/walletledger/ledger/UnguardedTransferRaceIT.java)
is the test that proves it, and it passes when it succeeds in proving it.** Twelve threads, forty
transfers each, all released together by a `CountDownLatch`, against one pair of accounts. It
asserts the weakest claim available: a transfer cannot change how much money exists.

It always changes. Across 11 runs on one machine, 480 concurrent transfers of 100 moved the total
by between 200 lost and 2,400 created out of nothing, and every run produced the discrepancy on
the first attempt.

A test suite that only shows the fixed version working is asking to be believed about what was
wrong with the old one. This runs the old one.

## Three ways to fix it, and what each costs

All three satisfy the same test.
[`ConcurrentTransferConservationIT`](src/test/java/com/iliyanadov/walletledger/ledger/ConcurrentTransferConservationIT.java)
runs the identical workload against each and asserts two things: the total across both accounts is
unchanged, and the payer is down by exactly what the successful transfers moved. Conservation
alone is not enough, because a ledger can conserve the total and still be wrong about which
transfers happened.

### `synchronized`

One monitor covers the whole ledger, so one unit of work runs at a time and the race has nowhere
to happen.

It serialises accounts that have nothing to do with each other. A deposit into one account waits
behind a transfer between two others it will never touch. A monitor per account would fix the
throughput and bring back the deadlock described below, so it would then need the same ordering
the pessimistic strategy uses.

The real cost is that it stops working when a second instance starts. A monitor is a fact about
one heap. Two processes against one database have two monitors and no shared answer between them,
and what comes back is the original race, at a rate nobody can reproduce locally because it takes
two instances to appear at all. Of the three, this is the only one that gets quietly less correct
as the deployment grows.

The lock is taken outside the transaction, which is not a detail. The writes are queued at the
database and only land when the transaction commits, so a lock released before that commit lets
the next thread start reading while the previous write is still in flight. That window is the one
the lock exists to close. It is the standard way of getting this wrong with `@Transactional`,
where `synchronized` inside the method body releases the monitor at the closing brace and the
proxy commits afterwards.

### JPA `@Version`, optimistic

Nothing is locked. Each account carries a version column, Hibernate puts the version it read into
the `WHERE` clause of the `UPDATE`, and an update that matches no row means somebody else wrote
first. The transaction rolls back and the whole unit of work runs again against the balances that
won.

The retry has to be outside the transaction. Once a transaction has failed its version check it is
finished, and anything attempted inside it fails too. Retrying means a new transaction and a fresh
read, which is why a unit of work here has to re-read everything it touches rather than carry an
account across attempts.

The cost is that it does the work more than once, and it gets worse exactly where it hurts. Across
11 runs, 480 concurrent transfers against a single pair of accounts produced between 326 and 345
successes and between 1,620 and 1,802 retries. The rest, 135 to 154 a run, ran out of retry
attempts and returned 503. That is the
right answer for a caller under contention, and it is far better than retrying forever, which
turns a contention problem into an availability problem with no signal on it.

The backoff is randomised. Two callers that collide and then wait the same fixed pause collide
again, because nothing has broken the symmetry between them.

### Row locks, pessimistic

Every read holds the row until the transaction ends, so a second caller asking for the same
account waits in the database rather than reading a number that is about to change. The
correctness argument is the shortest of the three: there is no window, because the read and the
write are inside the same lock.

**The lock is not the one the annotation's name suggests, and the difference is worth knowing.**
`@Lock(PESSIMISTIC_WRITE)` on Postgres does not emit `SELECT ... FOR UPDATE`. Hibernate's
dialect turns it into `SELECT ... FOR NO KEY UPDATE`, which is the weaker of the two write locks.

Weaker is the right one to hold here. Postgres takes `FOR KEY SHARE` on a row whenever something
inserts a reference to it, so every settlement queued against an account takes one on that
account's row. `FOR UPDATE` conflicts with `FOR KEY SHARE` and would park those inserts behind
any transfer in progress, for no benefit at all. What `FOR UPDATE` protects on top of the weaker
lock is the row's key columns, here just the id, and queueing a settlement was never going to
change an account's id. `FOR NO KEY UPDATE` does
not conflict with it, and it still excludes another writer on the same row, which is the only
thing this lock is here for.

`PessimisticLockScopeIT` holds both halves: a settlement is queued while a transfer holds the
account row, and a second writer asking for that row waits. Swapping the repository for a
literal `for update` makes the first of those two time out.

**This is the default.** It is the only one of the three that is both correct across instances and
predictable under load, and predictable is usually worth more than fast. In all of the same 11 runs
it completed all 480 transfers with no retries, in between 494 and 1,099 ms.

The cost is that a transaction stays open for as long as the lock is held, so the slowest thing
inside that transaction sets the rate for everything queued behind it. Nothing slow belongs in
there. A lock held across an HTTP call to a dependency that is itself struggling is how one slow
service takes out a table.

The other cost is the deadlock.

## The deadlock

`transfer(A, B)` and `transfer(B, A)` running at the same time under row locks will stop dead.
Each one takes the row it needs first and then asks for the row the other is holding, and neither
can give way.

This is the failure with no single threaded version of itself. A race is two threads doing the
same thing in the wrong order, and you can talk yourself through it one step at a time. Here both
threads are behaving correctly, neither has done anything out of order, and the system stops
anyway.

[`PessimisticDeadlockIT`](src/test/java/com/iliyanadov/walletledger/ledger/PessimisticDeadlockIT.java)
builds one. Two threads, two accounts, locks taken in opposite orders, and a `CyclicBarrier` so
neither asks for its second lock until both are holding their first. Postgres waits for
`deadlock_timeout`, finds the cycle in the wait graph, and kills one of the two transactions with
SQLSTATE 40P01.

How that presents in production is the part worth knowing. It is not a hang. One request in some
small fraction fails, under load, and only when two particular transfers happen to cross. It looks
like flakiness, which is how it survives a code review and a week in staging.

The fix is ordering. `transfer` sorts the pair by account id before it acquires anything, so every
caller in the system queues for the lower id first. Two transfers going in opposite directions now
want the same lock first, one of them gets it, and the other waits for it instead of waiting for
something the winner is about to need. The cycle cannot form because nobody ever asks in the other
order. The second half of that test runs 360 crossed transfers through the ordered version and
none of them fail.

There is a quieter version of the same problem one level down. Two transactions that dirty the
same two rows will have Hibernate write them in whatever order it happened to load them, which can
deadlock with no locking code anywhere to blame. `hibernate.order_updates` sorts the statements in
a flush by table and primary key, so the write order is consistent too.

## Why `settle` may break the invariant and `transfer` may not

`settle` takes two numbers rather than one, and they are allowed to differ.

Reserve 500 against a position. The position loses 700. Settling it releases the 500 that was
promised and takes 700 out of the account. If another 500 is still promised elsewhere, the account
now owes more than it holds, and `locked > available`.

That is not a corrupted ledger. It is a margin call, and a system that refuses to represent it
cannot settle a loss at all. So `settle` is the one operation that does not assert the full
invariant. It still refuses to take either number below zero.

A transfer in exactly the same position is refused. The difference is not in the column, it is in
who is acting. A settlement is the market telling the account what happened to it. A transfer is
the holder choosing to move money out, and the holder does not get to walk away from a shortfall
by moving what is left.

The half of that rule which is easy to get wrong is what happens next. Once an account is
underwater it still has to be possible to pay into it, to release a promise on it and to settle
against it. Only money leaving and new promises are refused, and the check has to be written to
tell those apart rather than to test the invariant and stop.

- **Money in always works**, including an amount smaller than the shortfall. An account 1,000
  short that receives 500 is 500 short. Refusing that deposit because it does not clear the whole
  shortfall means the shortfall can never be cleared, since paying in is the only way to clear it.
  The same goes for the receiving half of a transfer, which is the same movement: refusing it
  would also roll back the payer, so a third party would be refused for the state of an account
  they do not own.
- **Releasing a promise always works.** It lowers `locked`, so it closes the gap. Refusing it
  would strand collateral on the one account that needs it back.
- **Money out is refused**, and so is promising more.

The rule underneath all three is that the gap may not grow. `credit` and `release` cannot grow it,
whatever the account looks like when they start, so they are checked against whether they made
things worse rather than against `locked <= available`. `debit` and `reserve` can grow it, so they
check what is spendable first and refuse with a 422 when it is not enough.

Getting that wrong is not a small mistake. Checking `locked <= available` after a credit means a
customer wiring in part of a margin call gets a 500, the transaction rolls back, and the service
logs an internal fault for what was an ordinary payment. `AccountTest.TheAsymmetry` and
`LedgerSemanticsIT` both cover it, the second against all four implementations.

The release and the debit have to be one step. Releasing first and debiting afterwards leaves a
window where the money is spendable and the loss has not landed, which is the same shape as every
other defect on this page.

## Idempotency

A client that does not get an answer does not know whether the payment happened, so it sends the
request again. Every endpoint that moves money on an existing account requires an
`Idempotency-Key` header, and the first caller to claim that key is the one that runs.

The claim is one Redis command. `SET key value NX PX ttl` decides whether the key was already
there and sets it in the same operation, so there is no read followed by a write for two callers
to get between. `EXISTS` followed by `SET` would be two operations and would have the same shape
as the unguarded ledger, in a different colour.

`IdempotencyGuardIT` fires 24 simultaneous claims at one key and asserts that exactly one wins.
`LedgerApiIT` does the same thing over HTTP with 16 concurrent submissions of one transfer, and
asserts one 200, fifteen 409s, and the money moved once.

Opening an account does not require one, and that is a decision rather than an oversight.
Refusing a duplicate is safe when the caller can find out what happened by reading the account
afterwards. It is not safe when the response to the duplicate was the only place the new id was
ever going to appear, because the caller is then left holding a 409 and no way to reach the
account it just created. Creation wants idempotency that replays the original response, which is
a different mechanism from the one here.

Two things this deliberately does not do. A duplicate is refused rather than replayed, so the
caller learns the request was already accepted but not what the original returned. And the key
carries a time to live, which stops the key set growing without limit and also bounds the
guarantee: a request cannot be replayed within the window, rather than never.

If the work fails, the key is handed back. Keeping a key for a request that did not happen is
worse than not having one, because the retry is the attempt that would have worked and it gets
refused as a duplicate of something that never took effect.

Which half of the request failed decides that, and the distinction is easy to get wrong. The
handler moves the money and then reads the accounts back for the response. Only the first of those
may release the key. Once the transfer has committed, a failure to read the balances, to serialise
the body, or to write the connection does not undo it, so the key has to stay claimed. Wrapping
both steps in one `try` would release the key after the money had already moved, the client would
retry because it got a 500, and the money would move twice. That is the failure the header exists
to prevent, reintroduced by the code meant to prevent it. `IdempotencyKeyLifecycleIT` holds both
halves of it.

## The settlement job

Settlements are queued and applied by a scheduled job, which is why `POST /settlements` answers
202 and not 200. The balances have not moved by the time the response is written.

The job must not run twice at once for the same account. Two runs overlapping means both read the
same pending instructions before either has marked anything, so both apply them, and the second
one is not a retry. It is a second movement of money.

The guard is a `ConcurrentHashMap.newKeySet()` of account ids. A boolean would hold one fact for
the whole service and could not say that account A is busy while account B is free, so it would
either let B through while A is running, which is the defect, or stop B, which turns an unrelated
account into a queue. `add` returning false is the test and the claim in one step.

The release is in a `finally`. Put it at the end of the try block instead and a job that throws
leaves its account in the set forever. Nothing crashes, nothing alerts, and that one account
silently stops settling while every other account carries on.

The set lives in one heap, so it does not coordinate two instances. Running more than one would
need the claim to happen in the database, by marking the rows taken in the same statement that
selects them. Same limitation as the `synchronized` strategy, and for the same reason.

An instruction that will not apply stops that account. The run gives up at that instruction and
reports which one it was, and nothing queued behind it is applied until it clears. That is
deliberate. Settlements for one account are ordered, and reaching past a stuck one means money
moves in an order nobody asked for, which is harder to unpick afterwards than a queue that
stopped. The cost is that one account stops settling, so it is made loud rather than quiet: the
log names the instruction, a `settlement.stalled` counter moves, and the health endpoint reports
settlement failing and for how long.

The instruction that could never have applied at all is refused when it is queued rather than
found later. `POST /settlements` checks the amount being released against what the account
actually has promised, so a request to release more than exists is a 422 at the door instead of a
202 followed by a queue that fails every second for the life of the row. That is a sanity check at the
door and not the guarantee: balances move between queueing and settling, so `Account.settle` is
still what decides.

## Health

`/actuator/health` reports how long settlement has been failing, not whether it is failing.

A boolean says broken. A timestamp says broken for how long, which is the only way to tell
recovering-right-now from recovery-died-four-minutes-ago-and-nothing-noticed. Failing for two
seconds during a database failover and failing for two hours because something is wedged produce
the same boolean and deserve different responses.

```json
{
  "status": "DOWN",
  "failingSince": "2026-01-01T12:01:00Z",
  "failingForSeconds": 240,
  "consecutiveFailures": 2,
  "lastSuccessAt": "2026-01-01T12:00:00Z"
}
```

`failingSince` is set on the first failure and left alone by the ones after it, so it stays the
moment the trouble started rather than sliding forward to the most recent attempt.

Settlement having never run at all is reported as `UNKNOWN` rather than `UP`. It is the absence of
an answer and not a healthy one, and reporting it as healthy is how a service whose scheduler
never started passes its own health check. Spring puts `UNKNOWN` at the healthy end of its status
order, so an `UNKNOWN` component never drags the aggregate below `UP` and the endpoint still
returns 200 at startup. That is the behaviour you want here: a settlement run that has not
happened yet is not a reason to pull the instance out of a load balancer. The detail is there for
whoever is actually reading it.

## Tests

JUnit 5 and Testcontainers, against a real Postgres and a real Redis. Not H2 and not an in memory
substitute. What is under test is which Postgres row lock Hibernate actually takes and what it
does and does not block, Postgres deciding which transaction loses a deadlock, and Redis
answering `SET NX` from one command. A substitute either does not have
those or has its own version of them, and a test that passes against a substitute has proved
something about the substitute.

The containers start once for the whole suite and are shared, which is why the build runs
everything in a single JVM.

| Test | What it establishes |
|---|---|
| `UnguardedTransferRaceIT` | concurrent transfers through the unguarded ledger change the total, and the same code is correct on one thread |
| `ConcurrentTransferConservationIT` | each of the three strategies survives the workload that breaks it |
| `PessimisticDeadlockIT` | crossed row locks deadlock, and ordering by account id removes it |
| `PessimisticLockScopeIT` | the row lock excludes another writer and does not block a foreign key check |
| `LedgerSemanticsIT` | all four implementations agree on the rules when nothing is competing with them |
| `AccountTest` | the arithmetic, the refusals, overflow, and the settle/transfer asymmetry |
| `SettlementRunnerTest` | one account cannot settle twice at once, unrelated accounts do not block, a throwing job releases its account |
| `SettlementSchedulerIT` | settling twice applies once, an unappliable instruction is refused at the door, and a stalled one is named |
| `IdempotencyGuardIT` | one winner out of 24 simultaneous claims, and the key expires |
| `SettlementHealthIndicatorTest` | the health report is a duration and the start of the trouble does not move |
| `IdempotencyKeyLifecycleIT` | a failure after the money moved keeps the key, and a failure before it moved gives it back |
| `LedgerApiIT` | the HTTP surface, including 16 concurrent submissions of one transfer moving money once |

Coverage is not a target here. The concurrency tests are the point.

## Layout

```
src/main/java/com/iliyanadov/walletledger/
  account/      the entity, the invariant, and the repository that can lock a row
  ledger/       the interface and its four implementations
  idempotency/  the Redis claim
  settlement/   the queue, the per account guard, the scheduler and the health signal
  web/          the HTTP surface and the mapping from refusals to status codes
src/main/resources/db/migration/   Flyway, one migration
src/test/java/...                  the suite above
docker-compose.yml                 Postgres, Redis and the service
```

`ledger.strategy` picks the implementation and defaults to `pessimistic`. The unguarded one is
selectable so the race can be watched through the API rather than only in a test, and the service
logs a warning at startup when it is.

## Stack

Java 21, Spring Boot 3.5, Spring Data JPA, PostgreSQL 16, Redis 7, Flyway, Gradle, JUnit 5,
Testcontainers, Actuator and Micrometer.
