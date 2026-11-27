package dev.reviewbot.shared.installations;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.reviewbot.shared.reviews.PostgresTestSupport;

class InstallationRepositoryTest extends PostgresTestSupport {

  private static final long INSTALLATION = 42L;
  private static final byte[] CIPHERTEXT = {1, 2, 3, 4, (byte) 0xFF};

  private final InstallationRepository repository = new InstallationRepository(JDBC);

  @Test
  void aStoredKeyIsPresentAndReadBackByteForByte() {
    JDBC.update(
        "INSERT INTO installations (installation_id, api_key_encrypted) VALUES (?, ?)",
        INSTALLATION,
        CIPHERTEXT);

    assertThat(repository.hasApiKey(INSTALLATION)).isTrue();
    assertThat(repository.findEncryptedApiKey(INSTALLATION)).hasValue(CIPHERTEXT);
  }

  @Test
  void anInstallationWithoutAKeyHasNone() {
    JDBC.update("INSERT INTO installations (installation_id) VALUES (?)", INSTALLATION);

    assertThat(repository.hasApiKey(INSTALLATION)).isFalse();
    assertThat(repository.findEncryptedApiKey(INSTALLATION)).isEmpty();
  }

  @Test
  void anUnknownInstallationHasNoKeyAndNoModel() {
    assertThat(repository.hasApiKey(INSTALLATION)).isFalse();
    assertThat(repository.findEncryptedApiKey(INSTALLATION)).isEmpty();
    assertThat(repository.findDefaultModel(INSTALLATION)).isEmpty();
  }

  @Test
  void anotherInstallationsKeyDoesNotCount() {
    JDBC.update(
        "INSERT INTO installations (installation_id, api_key_encrypted) VALUES (?, ?)",
        99L,
        CIPHERTEXT);

    assertThat(repository.hasApiKey(INSTALLATION)).isFalse();
  }

  @Test
  void theDefaultModelIsTheColumnDefaultUnlessSet() {
    JDBC.update("INSERT INTO installations (installation_id) VALUES (?)", INSTALLATION);
    assertThat(repository.findDefaultModel(INSTALLATION)).hasValue("claude-sonnet-4-6");

    JDBC.update(
        "UPDATE installations SET default_model = 'claude-opus-4-1' WHERE installation_id = ?",
        INSTALLATION);
    assertThat(repository.findDefaultModel(INSTALLATION)).hasValue("claude-opus-4-1");
  }
}
