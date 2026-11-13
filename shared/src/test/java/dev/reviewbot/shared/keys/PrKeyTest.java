package dev.reviewbot.shared.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PrKeyTest {

  @Test
  void rendersGoldenFormat() {
    assertThat(new PrKey(12345, "acme/app", 17).render()).isEqualTo("12345:acme/app:17");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "acme/app",
        "my-org/my-repo",
        "my.org/my.repo",
        "my_org/my_repo",
        "a/b-c.d_e",
        ".github/.github",
      })
  void parseInvertsRenderForRepoNamesWithDotsHyphensUnderscores(String repo) {
    PrKey original = new PrKey(9_876_543_210L, repo, 2_147_483_647);

    assertThat(PrKey.parse(original.render())).isEqualTo(original);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "12345",
        "12345:acme/app",
        "12345:acme/app:17:extra",
        "x:acme/app:17",
        "12345:acme/app:y",
        "12345:acme/app:",
        ":acme/app:17",
        "12345::17",
        "12345:   :17",
        "99999999999999999999:acme/app:17",
        "12345:acme/app:2147483648",
        "-1:acme/app:17",
        "12345:acme/app:-1",
        "0:acme/app:17",
        "12345:acme/app:0",
      })
  void parseRejectsMalformedKeys(String key) {
    assertThatThrownBy(() -> PrKey.parse(key)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+12345:acme/app:17",
        "012345:acme/app:17",
        "12345:acme/app:017",
        "12345:acme/app:+17",
        "١٢٣:acme/app:17", // Arabic-Indic digits, accepted by Long.parseLong
      })
  void parseRejectsNonCanonicalNumbers(String key) {
    assertThatThrownBy(() -> PrKey.parse(key))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "12345:acme/app", // wrong part count
        "x:acme/app:17", // non-numeric
        "0:acme/app:17", // rejected by the constructor
        "12345:app:17", // rejected by the constructor
        "+12345:acme/app:17", // non-canonical
      })
  void parseErrorMessagesNameTheKey(String key) {
    assertThatThrownBy(() -> PrKey.parse(key)).hasMessageContaining(key);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   ", "acme:app", "app", "acme/app ", "a/b/c", "/app", "acme/"})
  void constructorRejectsRepoThatIsNotOwnerSlashName(String repo) {
    assertThatThrownBy(() -> new PrKey(1, repo, 1)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void constructorRejectsInstallationIdBelowOne(long installationId) {
    assertThatThrownBy(() -> new PrKey(installationId, "acme/app", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void constructorRejectsPrNumberBelowOne(int prNumber) {
    assertThatThrownBy(() -> new PrKey(1, "acme/app", prNumber))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
