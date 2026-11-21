package dev.reviewbot.worker.engine;

import dev.reviewbot.shared.keys.PrKey;

/** The one thing a drainer asks of the dispatcher: may I exit now that my queue looked empty? */
@FunctionalInterface
interface Retirer {

  boolean tryRetire(PrKey key);
}
