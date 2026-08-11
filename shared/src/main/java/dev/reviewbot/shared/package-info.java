/**
 * Shared contracts for reviewbot: event envelope, DLQ envelope, PR key and Redis key builders,
 * Kafka header codecs, the {@code ReviewGenerator} port, failure classification and config models.
 *
 * <p>Both workers use the same types from here — a codec or key-builder mismatch between modules
 * would be a silent interop bug, so these live in exactly one place. The types themselves land in
 * epic E1; this package is the skeleton they land in.
 */
package dev.reviewbot.shared;
