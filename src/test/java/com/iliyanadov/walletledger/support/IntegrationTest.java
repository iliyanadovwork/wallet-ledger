package com.iliyanadov.walletledger.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Postgres and a real Redis, in containers, for every test that extends this.
 *
 * <p>Not H2 and not an in-memory fake. The things being tested here are {@code SELECT ... FOR
 * UPDATE}, Postgres deciding which transaction loses a deadlock, and Redis answering {@code SET
 * NX} from one command. A substitute either does not have those or has its own version of them,
 * and a test that passes against a substitute has proved something about the substitute.
 *
 * <p>The containers are started once in a static block rather than per class, and nothing stops
 * them. Testcontainers leaves a reaper running and it removes them when the JVM that started them
 * exits, so the whole suite pays for one Postgres and one Redis instead of one per test class.
 * This is why the build runs the tests in a single JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES;
    protected static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("wallet")
                .withUsername("wallet")
                .withPassword("wallet")
                .withCommand("postgres",
                        // Postgres waits this long before it goes looking for a deadlock. The
                        // default is a second, which PessimisticDeadlockIT would spend waiting.
                        "-c", "deadlock_timeout=250ms",
                        // Nothing in here has to survive the container being killed.
                        "-c", "fsync=off",
                        "-c", "synchronous_commit=off",
                        // The concurrency tests open a lot of connections at once.
                        "-c", "max_connections=200");

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);

        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
