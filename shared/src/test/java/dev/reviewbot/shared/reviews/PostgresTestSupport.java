package dev.reviewbot.shared.reviews;

import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** One migrated Postgres 16 per test JVM (Ryuk stops it); tables are emptied before each test. */
public abstract class PostgresTestSupport {

  private static final Path MIGRATIONS =
      Path.of(
              System.getProperty(
                  "reviewbot.migrations.dir", Path.of("..", "infra", "db", "migration").toString()))
          .toAbsolutePath()
          .normalize();

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  protected static final JdbcTemplate JDBC;

  static {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("filesystem:" + MIGRATIONS)
        .load()
        .migrate();
    JDBC =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @BeforeEach
  void emptyTables() {
    JDBC.execute("TRUNCATE reviews, installations RESTART IDENTITY");
  }
}
