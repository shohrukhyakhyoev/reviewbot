package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Severity.MAJOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FindingTest {

  @Test
  void keepsItsComponents() {
    Finding finding = new Finding("src/A.java", "Missing null check", MAJOR, "x may be null here");

    assertThat(finding.file()).isEqualTo("src/A.java");
    assertThat(finding.summary()).isEqualTo("Missing null check");
    assertThat(finding.severity()).isEqualTo(MAJOR);
    assertThat(finding.detail()).isEqualTo("x may be null here");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsNullOrBlankFile(String file) {
    assertThatThrownBy(() -> new Finding(file, "s", MAJOR, "d"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("file");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsNullOrBlankSummary(String summary) {
    assertThatThrownBy(() -> new Finding("f", summary, MAJOR, "d"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("summary");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  void rejectsNullOrBlankDetail(String detail) {
    assertThatThrownBy(() -> new Finding("f", "s", MAJOR, detail))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("detail");
  }

  @Test
  void rejectsNullSeverity() {
    assertThatThrownBy(() -> new Finding("f", "s", null, "d"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("severity");
  }
}
