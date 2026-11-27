package dev.reviewbot.shared.installations;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read side of {@code installations} (§5.1). The key stays ciphertext here: it is decrypted only in
 * the adapter call path (E5-3), and is never put in a record, whose toString would print it.
 */
public final class InstallationRepository {

  private final JdbcTemplate jdbc;

  public InstallationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** §7.1 step 2.6: false for no row, no key, or a cleared key. Does not load the key bytes. */
  public boolean hasApiKey(long installationId) {
    return jdbc.queryForObject(
        """
        SELECT EXISTS (
          SELECT 1 FROM installations
           WHERE installation_id = ? AND api_key_encrypted IS NOT NULL
        )
        """,
        Boolean.class,
        installationId);
  }

  /** The AES-GCM ciphertext of the BYOK key; empty when there is no row or no key. */
  public Optional<byte[]> findEncryptedApiKey(long installationId) {
    return jdbc
        .query(
            "SELECT api_key_encrypted FROM installations WHERE installation_id = ?",
            (rs, rowNum) -> Optional.ofNullable(rs.getBytes("api_key_encrypted")),
            installationId)
        .stream()
        .findFirst()
        .flatMap(key -> key);
  }

  /** The installation's model; empty for an unknown installation (the column default lives in SQL). */
  public Optional<String> findDefaultModel(long installationId) {
    return jdbc
        .query(
            "SELECT default_model FROM installations WHERE installation_id = ?",
            (rs, rowNum) -> rs.getString("default_model"),
            installationId)
        .stream()
        .findFirst();
  }
}
