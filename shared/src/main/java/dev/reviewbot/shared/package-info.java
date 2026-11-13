/**
 * Contracts shared by the gateway and both workers: envelopes, PR and Redis keys, header codecs,
 * the {@code ReviewGenerator} port, failure classification and config models. They live here so a
 * format mismatch between modules cannot happen.
 */
package dev.reviewbot.shared;
