package dev.reviewbot.shared.envelope;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The topic value shared by the gateway (producer) and both workers (consumers) — spec §3.1.
 *
 * <p>Every component is a nullable boxed type on purpose: Jackson binds a missing JSON field to
 * {@code null}, and {@link EnvelopeValidator} turns that into a typed failure (spec §3.3 rule 4,
 * §19.5 — tolerant reader plus explicit validation, never annotation-required). The only
 * exceptions are the primitive {@code force}/{@code replay}, which default to {@code false} when absent.
 *
 * <p>A bound envelope is <b>not yet trusted</b>: always read it through {@link EnvelopeSerde}, which
 * validates after binding.
 *
 * <p>Evolution (§3.3): additive only — new fields optional with defaults; never rename, remove or
 * retype one.
 */
public record ReviewRequestedEnvelope(
    @JsonProperty("schema_version") Integer schemaVersion,
    @JsonProperty("event_id") String eventId,
    @JsonProperty("delivery_id") String deliveryId,
    @JsonProperty("type") EventType type,
    @JsonProperty("occurred_at") Instant occurredAt,
    @JsonProperty("installation_id") Long installationId,
    @JsonProperty("repo_full_name") String repoFullName,
    @JsonProperty("pr_number") Integer prNumber,
    @JsonProperty("head_sha") String headSha,
    // Primitives: absent (or JSON null) binds to false, which is the §3.1 default. Never null.
    @JsonProperty("force") boolean force,
    @JsonProperty("replay") boolean replay) {

  /** The only schema version v1 accepts (§3.3 rule 4). */
  public static final int SCHEMA_VERSION = 1;
}
