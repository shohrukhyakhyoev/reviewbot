package dev.reviewbot.worker.engine;

import dev.reviewbot.shared.keys.PrKey;

/** Builds the task that drains one PR's queue; the dispatcher starts it on a fresh thread. */
@FunctionalInterface
interface DrainerFactory {

  Runnable create(PrKey key, PrQueue queue, Retirer retirer);
}
