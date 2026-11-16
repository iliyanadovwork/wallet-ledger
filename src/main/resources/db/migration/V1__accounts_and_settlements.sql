-- Money is stored as BIGINT minor units. There is no numeric or double column in this schema and
-- there is not supposed to be: a balance held as a floating point number will eventually disagree
-- with itself about whether (r - a) - b and r - (a + b) are the same, and a ledger cannot afford
-- to be unsure about that.
create table account (
    id        uuid   primary key,
    available bigint not null,
    locked    bigint not null,
    version   integer not null default 0,

    -- Half of the invariant, and only half on purpose. Neither number may go negative, and that
    -- holds no matter who wrote the row, so the database can enforce it and catch anything that
    -- reaches the table without going through the application.
    --
    -- The other half, locked <= available, is not here, because settling a loss larger than the
    -- amount reserved against it is allowed to break it. That rule depends on which operation is
    -- running and a column constraint cannot see which operation is running. It lives in
    -- Account instead.
    constraint account_balances_non_negative check (available >= 0 and locked >= 0)
);

create table settlement_instruction (
    id         uuid        primary key,
    account_id uuid        not null references account (id),
    released   bigint      not null,
    settled    bigint      not null,
    created_at timestamptz not null,
    settled_at timestamptz,

    constraint settlement_amounts_valid check (released > 0 and settled >= 0)
);

-- The scheduler only ever asks for work that is still waiting, so the index only covers those
-- rows. Applied instructions stay in the table as the record of what happened and stay out of
-- the index.
create index settlement_instruction_pending
    on settlement_instruction (account_id)
    where settled_at is null;
