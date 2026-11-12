package dev.reviewbot.shared.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * E2-3 schema smoke test: applies {@code infra/db/migration} to an empty Postgres 16 with
 * programmatic Flyway (apps do not self-migrate, O1) and asserts the §5.1 schema is all there.
 */
@Testcontainers
class SchemaMigrationTest {

  /** Surefire runs with the module directory as cwd; the migrations live in the plain infra/ dir. */
  private static final Path MIGRATIONS =
      Path.of(
              System.getProperty(
                  "reviewbot.migrations.dir", Path.of("..", "infra", "db", "migration").toString()))
          .toAbsolutePath()
          .normalize();

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  private static MigrateResult firstRun;
  private static MigrateResult secondRun;

  @BeforeAll
  static void migrateTwice() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("filesystem:" + MIGRATIONS)
            .load();
    firstRun = flyway.migrate();
    secondRun = flyway.migrate();
  }

  @Test
  void appliesCleanlyOnEmptyDatabase() {
    assertThat(firstRun.success).isTrue();
    assertThat(firstRun.migrationsExecuted).isEqualTo(1);
  }

  @Test
  void rerunIsNoOp() {
    assertThat(secondRun.success).isTrue();
    assertThat(secondRun.migrationsExecuted).isZero();
  }

  @Test
  void createsAllThreeTables() throws SQLException {
    assertThat(
            queryColumn(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name IN"
                    + " ('reviews', 'installations', 'dlq_entries')"))
        .containsExactlyInAnyOrder("reviews", "installations", "dlq_entries");
  }

  @Test
  void reviewsHasUniqueOnPrIdentityAndRound() throws SQLException {
    assertThat(uniqueConstraintColumns("reviews"))
        .containsExactly(
            List.of("installation_id", "repo_full_name", "pr_number", "head_sha", "round"));
  }

  @Test
  void dlqEntriesHasUniqueOnPartitionAndOffset() throws SQLException {
    assertThat(uniqueConstraintColumns("dlq_entries"))
        .containsExactly(List.of("dlq_partition", "dlq_offset"));
  }

  @Test
  void createsAllFourIndexes() throws SQLException {
    assertThat(
            queryColumn(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
                    + " AND indexname IN ('idx_reviews_rate', 'idx_reviews_last',"
                    + " 'idx_dlq_status', 'idx_dlq_tenant')"))
        .containsExactlyInAnyOrder(
            "idx_reviews_rate", "idx_reviews_last", "idx_dlq_status", "idx_dlq_tenant");
  }

  @Test
  void reviewsLastIndexIsPartialOnSucceeded() throws SQLException {
    List<String> defs =
        queryColumn("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_reviews_last'");
    assertThat(defs).hasSize(1);
    assertThat(defs.get(0))
        .contains("(installation_id, repo_full_name, pr_number, completed_at DESC)")
        .contains("WHERE (status = 'SUCCEEDED'::text)");
  }

  @Test
  void indexColumnListsAndOrderMatchSpec() throws SQLException {
    assertThat(indexDef("idx_reviews_rate")).endsWith("USING btree (installation_id, status)");
    assertThat(indexDef("idx_dlq_status")).endsWith("USING btree (status, failure_class)");
    assertThat(indexDef("idx_dlq_tenant")).endsWith("USING btree (installation_id, failure_class)");
  }

  @Test
  void roundDefaultsToOne() throws SQLException {
    assertThat(columnDefault("reviews", "round")).isEqualTo("1");
  }

  @Test
  void fenceDefaultsToZero() throws SQLException {
    assertThat(columnDefault("reviews", "fence")).isEqualTo("0");
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static List<String> queryColumn(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }

  /** The full CREATE INDEX text; {@code endsWith} also proves the index is not partial. */
  private static String indexDef(String indexName) throws SQLException {
    List<String> defs =
        queryColumn("SELECT indexdef FROM pg_indexes WHERE indexname = '" + indexName + "'");
    assertThat(defs).hasSize(1);
    return defs.get(0);
  }

  private static String columnDefault(String table, String column) throws SQLException {
    List<String> defaults =
        queryColumn(
            "SELECT column_default FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'");
    assertThat(defaults).hasSize(1);
    return defaults.get(0);
  }

  /** Column lists (in constraint order) of every UNIQUE constraint on the table. */
  private static List<List<String>> uniqueConstraintColumns(String table) throws SQLException {
    List<List<String>> result = new ArrayList<>();
    String sql =
        "SELECT c.conname, a.attname FROM pg_constraint c"
            + " JOIN pg_class t ON t.oid = c.conrelid"
            + " CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)"
            + " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum"
            + " WHERE c.contype = 'u' AND t.relname = '"
            + table
            + "' ORDER BY c.conname, k.ord";
    String current = null;
    List<String> columns = null;
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        if (!rs.getString(1).equals(current)) {
          current = rs.getString(1);
          columns = new ArrayList<>();
          result.add(columns);
        }
        columns.add(rs.getString(2));
      }
    }
    return result;
  }
}
