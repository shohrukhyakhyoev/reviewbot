package dev.reviewbot.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.reviewbot.shared.review.Severity;

class RepoConfigParserTest {

  private final List<String> problems = new ArrayList<>();

  private RepoConfig parse(String yaml) {
    return RepoConfigParser.parse(yaml, problems::add);
  }

  @Test
  void aFullFileBindsEveryKey() {
    var config =
        parse(
            """
            model: claude-opus-4-1
            min_severity: MAJOR
            review_authors: contributors
            """);

    assertThat(config).isEqualTo(new RepoConfig("claude-opus-4-1", Severity.MAJOR, ReviewAuthors.CONTRIBUTORS));
    assertThat(problems).isEmpty();
  }

  @Test
  void defaultsAreNoOverrideNitAndWriteOnly() {
    assertThat(RepoConfig.DEFAULTS)
        .isEqualTo(new RepoConfig(null, Severity.NIT, ReviewAuthors.WRITE));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   \n", "---\n", "# only a comment\n", "null\n", "{}"})
  void missingOrEmptyFileIsDefaultsAndNotAProblem(String yaml) {
    assertThat(parse(yaml)).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).isEmpty();
  }

  @Test
  void keysThatAreAbsentKeepTheirDefaults() {
    assertThat(parse("min_severity: minor\n"))
        .isEqualTo(new RepoConfig(null, Severity.MINOR, ReviewAuthors.WRITE));
  }

  @Test
  void explicitNullKeepsTheDefault() {
    assertThat(parse("model:\nreview_authors: ~\n")).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).isEmpty();
  }

  @Test
  void unknownKeysAreIgnored() {
    var config = parse("future_option: {a: 1}\nmin_severity: CRITICAL\nreview_drafts: true\n");

    assertThat(config).isEqualTo(new RepoConfig(null, Severity.CRITICAL, ReviewAuthors.WRITE));
    assertThat(problems).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"NIT,NIT", "nit,NIT", "Minor,MINOR", "'  major ',MAJOR", "critical,CRITICAL"})
  void severityIsCaseInsensitiveAndTrimmed(String value, Severity expected) {
    assertThat(parse("min_severity: '" + value + "'\n").minSeverity()).isEqualTo(expected);
    assertThat(problems).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"write,WRITE", "CONTRIBUTORS,CONTRIBUTORS", "All,ALL"})
  void everyReviewAuthorsValueParses(String value, ReviewAuthors expected) {
    assertThat(parse("review_authors: " + value + "\n").reviewAuthors()).isEqualTo(expected);
    assertThat(problems).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "min_severity: [unclosed\n",
        "model: a\n  bad: indent\n:\n",
        "\t: tab\n",
        "- just\n- a list\n",
        "just a scalar\n",
        "min_severity: {a: b}\n",
        "model: 42\n",
        "model: ''\n",
        "model: [a, b]\n"
      })
  void malformedOrMistypedFileGivesAllDefaultsAndOneProblem(String yaml) {
    assertThat(parse(yaml)).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).hasSize(1);
  }

  @Test
  void aRepeatedKeyIsAProblemNotLastWins() {
    assertThat(parse("model: first\nmodel: second\n")).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).hasSize(1);
  }

  @Test
  void aYamlAliasIsAProblemNotTheAnchorName() {
    // Jackson reports `*m` as the text "m", so without a check model would silently become "m".
    assertThat(parse("base: &m claude-opus-4-1\nmodel: *m\n")).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).singleElement().asString().contains("alias");
  }

  @Test
  void hostileYamlNeverEscapesAsAnException() {
    var deeplyNested = "[".repeat(100_000);
    var aliasBomb =
        "a: &a [x, x, x, x, x, x, x, x, x]\n"
            + "b: &b [*a, *a, *a, *a, *a, *a, *a, *a, *a]\n"
            + "c: &c [*b, *b, *b, *b, *b, *b, *b, *b, *b]\n"
            + "d: &d [*c, *c, *c, *c, *c, *c, *c, *c, *c]\n"
            + "e: [*d, *d, *d, *d, *d, *d, *d, *d, *d]\n";

    assertThat(parse(deeplyNested)).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).hasSize(1);
    // Whether the library refuses this is its business; the contract is only "never throws".
    assertThat(parse(aliasBomb)).isEqualTo(RepoConfig.DEFAULTS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"urgent", "", "7", "NITS", "true"})
  void invalidSeverityDiscardsTheWholeFileIncludingValidKeys(String bad) {
    var config = parse("model: claude-opus-4-1\nreview_authors: all\nmin_severity: '" + bad + "'\n");

    assertThat(config).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).singleElement().asString().contains("min_severity");
  }

  @Test
  void invalidReviewAuthorsDiscardsTheWholeFile() {
    var config = parse("model: claude-opus-4-1\nreview_authors: everyone\n");

    assertThat(config).isEqualTo(RepoConfig.DEFAULTS);
    assertThat(problems).singleElement().asString().contains("review_authors");
  }

  @Test
  void aProblemNeverEchoesFileContent() {
    parse("model: secret-token-abc\nmin_severity: leaked-value\n");
    parse("model: [secret-token-abc\n");

    assertThat(problems).hasSize(2).noneMatch(p -> p.contains("secret-token") || p.contains("leaked-value"));
  }

  @Test
  void malformedYamlReportsWhereNotWhat() {
    parse("a: 1\nb: [oops\n");

    assertThat(problems).singleElement().asString().startsWith("malformed YAML at line ");
  }

  @Test
  void theLoggingOverloadReturnsDefaultsInsteadOfThrowing() {
    assertThat(RepoConfigParser.parse("min_severity: bogus\n", "acme/app"))
        .isEqualTo(RepoConfig.DEFAULTS);
    assertThat(RepoConfigParser.parse(null, "acme/app")).isEqualTo(RepoConfig.DEFAULTS);
  }

  @Test
  void blankModelIsRejectedByTheRecord() {
    assertThatThrownBy(() -> new RepoConfig(" ", Severity.NIT, ReviewAuthors.WRITE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullSeverityOrAuthorsAreRejectedByTheRecord() {
    assertThatThrownBy(() -> new RepoConfig(null, null, ReviewAuthors.WRITE))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RepoConfig(null, Severity.NIT, null))
        .isInstanceOf(NullPointerException.class);
  }
}
