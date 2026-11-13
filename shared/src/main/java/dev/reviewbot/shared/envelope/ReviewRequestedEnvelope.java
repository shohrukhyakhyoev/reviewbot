package dev.reviewbot.shared.envelope;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The topic value (spec §3.1). Components are boxed on purpose: a missing JSON field binds to
 * {@code null} and {@link EnvelopeValidator} reports it (§3.3 rule 4). {@code force}/{@code replay}
 * are primitives that default to {@code false}.
 *
 * <p>A bound envelope is not yet trusted: read it only through {@link EnvelopeSerde}. Evolution is
 * additive only (§3.3): never rename, remove or retype a field.
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
    @JsonProperty("force") boolean force,
    @JsonProperty("replay") boolean replay) {

  public static final int SCHEMA_VERSION = 1;
}
