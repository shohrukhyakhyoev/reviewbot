package dev.reviewbot.shared.review;

import java.util.List;
import java.util.Objects;

final class Checks {

  private Checks() {}

  static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be null or blank");
    }
  }

  // Not List.copyOf alone: it throws NullPointerException, and every other check here throws IAE.
  static <T> List<T> immutableCopy(List<T> list, String name) {
    if (list == null || list.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(name + " must not be null or contain null");
    }
    return List.copyOf(list);
  }
}
