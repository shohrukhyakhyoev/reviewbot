package dev.reviewbot.shared.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DiffFileTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void rejectsNullOrBlankPath(String path) {
    assertThatThrownBy(() -> new DiffFile(path, "p", "added"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("path");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void rejectsNullOrBlankStatus(String status) {
    assertThatThrownBy(() -> new DiffFile("A.java", "p", status))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("status");
  }

  @Test
  void allowsAnEmptyPatch() {
    assertThat(new DiffFile("img.png", "", "added").patch()).isEmpty();
  }

  @Test
  void rejectsANullPatch() {
    assertThatThrownBy(() -> new DiffFile("img.png", null, "added"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("patch");
  }
}
