package dev.reviewbot.shared.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class DiffTest {

  private static final DiffFile FILE = new DiffFile("A.java", "@@ -1 +1 @@", "modified");

  @Test
  void copiesTheListSoLaterChangesToTheSourceDoNotLeakIn() {
    List<DiffFile> source = new ArrayList<>(List.of(FILE));
    Diff diff = new Diff(source);

    source.clear();

    assertThat(diff.files()).containsExactly(FILE);
  }

  @Test
  void rejectsANullList() {
    assertThatThrownBy(() -> new Diff(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("files");
  }

  @Test
  void rejectsANullElement() {
    assertThatThrownBy(() -> new Diff(Arrays.asList(FILE, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("files");
  }
}
