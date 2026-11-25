package dev.reviewbot.worker.engine;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

/**
 * Wraps a real {@code KafkaConsumer}: counts polls, delivered records and rebalance callbacks, and
 * can make the next poll throw so the poller dies without its final commit (ADR 0004's fatal path).
 */
final class ConsumerProbe implements InvocationHandler {

  static final class SimulatedCrash extends Error {
    private static final long serialVersionUID = 1L;

    SimulatedCrash() {
      super("simulated instance crash");
    }
  }

  private final Consumer<String, byte[]> target;
  private final AtomicInteger pollCalls = new AtomicInteger();
  private final AtomicInteger recordsDelivered = new AtomicInteger();
  private final AtomicInteger rebalanceCallbacks = new AtomicInteger();
  private final Set<TopicPartition> handedOver = ConcurrentHashMap.newKeySet();
  private volatile boolean crashing;
  private volatile Runnable onRevocationStart = () -> {};

  ConsumerProbe(Consumer<String, byte[]> target) {
    this.target = target;
  }

  @SuppressWarnings("unchecked")
  Consumer<String, byte[]> consumer() {
    return (Consumer<String, byte[]>)
        Proxy.newProxyInstance(
            Consumer.class.getClassLoader(), new Class<?>[] {Consumer.class}, this);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    return switch (method.getName()) {
      case "poll" -> countedPoll(method, args);
      case "subscribe" -> delegate(method, withCountingListener(args));
      default -> delegate(method, args);
    };
  }

  /** Runs on the poller thread as a revocation begins, before the engine's own callback. */
  void onRevocationStart(Runnable action) {
    onRevocationStart = action;
  }

  void crashOnNextPoll() {
    crashing = true;
  }

  int pollCalls() {
    return pollCalls.get();
  }

  int recordsDelivered() {
    return recordsDelivered.get();
  }

  int rebalanceCallbacks() {
    return rebalanceCallbacks.get();
  }

  /**
   * Partitions whose revocation callback has returned. After a rebalance that means grace, handover
   * commit and unregister are done; on {@code close()} the engine skips all three.
   */
  Set<TopicPartition> handedOver() {
    return Set.copyOf(handedOver);
  }

  private ConsumerRecords<?, ?> countedPoll(Method method, Object[] args) throws Throwable {
    if (crashing) {
      throw new SimulatedCrash();
    }
    pollCalls.incrementAndGet();
    var records = (ConsumerRecords<?, ?>) delegate(method, args);
    recordsDelivered.addAndGet(records.count());
    return records;
  }

  private Object[] withCountingListener(Object[] args) {
    if (args.length == 2 && args[1] instanceof ConsumerRebalanceListener listener) {
      args[1] = counting(listener);
    }
    return args;
  }

  private Object delegate(Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private ConsumerRebalanceListener counting(ConsumerRebalanceListener listener) {
    return new ConsumerRebalanceListener() {
      @Override
      public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        rebalanceCallbacks.incrementAndGet();
        listener.onPartitionsAssigned(partitions);
      }

      @Override
      public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        rebalanceCallbacks.incrementAndGet();
        onRevocationStart.run();
        listener.onPartitionsRevoked(partitions);
        handedOver.addAll(partitions);
      }

      @Override
      public void onPartitionsLost(Collection<TopicPartition> partitions) {
        rebalanceCallbacks.incrementAndGet();
        listener.onPartitionsLost(partitions);
      }
    };
  }
}
