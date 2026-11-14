package dev.reviewbot.shared.review;

import static dev.reviewbot.shared.review.Severity.CRITICAL;
import static dev.reviewbot.shared.review.Severity.MAJOR;
import static dev.reviewbot.shared.review.Severity.MINOR;
import static dev.reviewbot.shared.review.Severity.NIT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SeverityTest {

  @ParameterizedTest(name = "{0}.atOrAbove({1}) = {2}")
  @MethodSource("matrix")
  void atOrAbove(Severity value, Severity min, boolean expected) {
    assertThat(value.atOrAbove(min)).isEqualTo(expected);
  }

  @Test
  void matrixHasEveryDistinctPair() {
    long distinctPairs = matrix().map(row -> List.of(row.get()[0], row.get()[1])).distinct().count();

    assertThat(distinctPairs).isEqualTo(Severity.values().length * Severity.values().length);
  }

  static Stream<Arguments> matrix() {
    return Stream.of(
        Arguments.of(CRITICAL, NIT, true),
        Arguments.of(MAJOR, NIT, true),
        Arguments.of(MINOR, NIT, true),
        Arguments.of(NIT, NIT, true),
        Arguments.of(CRITICAL, MINOR, true),
        Arguments.of(MAJOR, MINOR, true),
        Arguments.of(MINOR, MINOR, true),
        Arguments.of(NIT, MINOR, false),
        Arguments.of(CRITICAL, MAJOR, true),
        Arguments.of(MAJOR, MAJOR, true),
        Arguments.of(MINOR, MAJOR, false),
        Arguments.of(NIT, MAJOR, false),
        Arguments.of(CRITICAL, CRITICAL, true),
        Arguments.of(MAJOR, CRITICAL, false),
        Arguments.of(MINOR, CRITICAL, false),
        Arguments.of(NIT, CRITICAL, false));
  }
}
