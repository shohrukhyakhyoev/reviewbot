package dev.reviewbot.shared.config;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.reviewbot.shared.review.Severity;

/** Lenient {@code .reviewbot.yml} parser (§7.1 step 7): any problem gives the defaults. */
public final class RepoConfigParser {

  private static final Logger LOG = LoggerFactory.getLogger(RepoConfigParser.class);
  // A repeated key is a mistake we report; the default would silently keep the last one.
  private static final YAMLMapper YAML =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private RepoConfigParser() {}

  /** Logs a problem against {@code repo} (e.g. {@code acme/app}) and returns the defaults. */
  public static RepoConfig parse(String yaml, String repo) {
    return parse(
        yaml, problem -> LOG.warn("ignoring .reviewbot.yml of {}, using defaults: {}", repo, problem));
  }

  /** {@code onProblem} gets a reason that never contains file content. */
  public static RepoConfig parse(String yaml, Consumer<String> onProblem) {
    if (yaml == null || yaml.isBlank()) {
      return RepoConfig.DEFAULTS;
    }
    try {
      rejectAliases(yaml);
      return bind(YAML.readTree(yaml));
    } catch (IOException e) {
      onProblem.accept(describe(e));
    } catch (InvalidConfigException e) {
      onProblem.accept(e.getMessage());
    } catch (RuntimeException e) {
      // The file is repo-author input: whatever the YAML library throws must not fail a review.
      onProblem.accept("unreadable YAML (" + e.getClass().getSimpleName() + ")");
    }
    return RepoConfig.DEFAULTS;
  }

  // Jackson reports the alias `*m` as the plain text "m", so a binder cannot tell it from a value.
  private static void rejectAliases(String yaml) throws IOException, InvalidConfigException {
    try (var parser = (YAMLParser) YAML.createParser(yaml)) {
      while (parser.nextToken() != null) {
        if (parser.isCurrentAlias()) {
          throw new InvalidConfigException("YAML aliases are not supported");
        }
      }
    }
  }

  private static String describe(IOException e) {
    if (e instanceof JsonProcessingException json && json.getLocation() != null) {
      var at = json.getLocation();
      return "malformed YAML at line " + at.getLineNr() + ", column " + at.getColumnNr();
    }
    return "malformed YAML";
  }

  private static RepoConfig bind(JsonNode root) throws InvalidConfigException {
    if (root.isMissingNode() || root.isNull()) {
      return RepoConfig.DEFAULTS;
    }
    if (!root.isObject()) {
      throw new InvalidConfigException("top level must be a mapping");
    }
    var model = text(root, "model");
    var minSeverity =
        enumValue(root, "min_severity", Severity.class, RepoConfig.DEFAULTS.minSeverity());
    var reviewAuthors =
        enumValue(root, "review_authors", ReviewAuthors.class, RepoConfig.DEFAULTS.reviewAuthors());
    try {
      return new RepoConfig(model, minSeverity, reviewAuthors);
    } catch (IllegalArgumentException e) {
      throw new InvalidConfigException(e.getMessage());
    }
  }

  // A YAML number or list under a text key is an error, not something to stringify.
  private static String text(JsonNode root, String key) throws InvalidConfigException {
    var node = root.get(key);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isTextual()) {
      throw new InvalidConfigException(key + " must be a string");
    }
    return node.asText().strip();
  }

  private static <E extends Enum<E>> E enumValue(
      JsonNode root, String key, Class<E> type, E fallback) throws InvalidConfigException {
    var value = text(root, key);
    if (value == null) {
      return fallback;
    }
    return Arrays.stream(type.getEnumConstants())
        .filter(constant -> constant.name().equals(value.toUpperCase(Locale.ROOT)))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidConfigException(
                    key + " must be one of " + Arrays.toString(type.getEnumConstants())));
  }

  private static final class InvalidConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidConfigException(String message) {
      super(message);
    }
  }
}
