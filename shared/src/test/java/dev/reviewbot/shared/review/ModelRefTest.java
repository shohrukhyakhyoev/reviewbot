package dev.reviewbot.shared.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ModelRefTest {

  @Test
  void keepsItsId() {
    assertThat(new ModelRef("some-model").id()).isEqualTo("some-model");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  void rejectsNullOrBlankId(String id) {
    assertThatThrownBy(() -> new ModelRef(id))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }
}
