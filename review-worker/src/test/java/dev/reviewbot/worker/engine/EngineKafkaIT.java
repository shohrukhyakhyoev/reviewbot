package dev.reviewbot.worker.engine;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import dev.reviewbot.shared.config.ReviewbotProperties;
import dev.reviewbot.shared.config.Topics;
import dev.reviewbot.shared.envelope.EnvelopeSerde;
import dev.reviewbot.shared.envelope.EventType;
import dev.reviewbot.shared.envelope.ReviewRequestedEnvelope;
import dev.reviewbot.shared.keys.PrKey;
import dev.reviewbot.worker.engine.ExecutionLog.Completion;
import dev.reviewbot.worker.engine.ExecutionLog.Execution;
import dev.reviewbot.worker.engine.ExecutionLog.RecordId;
import dev.reviewbot.worker.kafka.ConsumerSettings;

/** §18: the engine against a real broker, asserted through invariants, never through sleeps. */
@Testcontainers
class EngineKafkaIT {

  private static final Logger LOG = LoggerFactory.getLogger(EngineKafkaIT.class);

  @Container
  private static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
          .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

  // §12 sizing (100 / 200 / 100); timeouts shrunk so a stall can outlast max.poll.interval
  // quickly. 6 s is the broker's minimum session timeout.
  private static final ReviewbotProperties.Consumer SETTINGS =
      new ReviewbotProperties.Consumer(
          /* sessionTimeout= */ Duration.ofSeconds(6),
          /* heartbeatInterval= */ Duration.ofSeconds(2),
          /* maxPollInterval= */ Duration.ofSeconds(10),
          /* pollTimeout= */ Duration.ofMillis(100),
          /* maxPollRecords= */ 100,
          /* inFlightCap= */ 200,
          /* resumeThreshold= */ 100,
          /* shutdownGrace= */ Duration.ofSeconds(10),
          /* revocationGrace= */ Duration.ofSeconds(3),
          /* kafkaCallTimeout= */ Duration.ofSeconds(10));
  private static final Duration DEADLINE = Duration.ofSeconds(90);
  private static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  private static final AtomicLong SEQUENCE = new AtomicLong();

  private static Admin admin;
  private static KafkaProducer<String, byte[]> producer;

  private final long seed = Long.getLong("engine.it.seed", System.nanoTime());
  private final String group = "engine-it-" + UUID.randomUUID();
  private final ExecutionLog log = new ExecutionLog();
  private final List<Instance> instances = new ArrayList<>();
  private final List<Load> loads = new ArrayList<>();
  private final Map<Integer, Long> startOffsets = new HashMap<>();

  @BeforeAll
  static void createTopicAndClients() throws Exception {
    admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap()));
    admin
        .createTopics(
            List.of(
                new NewTopic(
                    Topics.REVIEW_REQUESTED, Topics.REVIEW_REQUESTED_PARTITIONS, (short) 1)))
        .all()
        .get();
    producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    ByteArraySerializer.class.getName()));
  }

  @AfterAll
  static void closeClients() {
    producer.close();
    admin.close();
  }

  // The topic outlives each test; starting the fresh group at the log end scopes it to this
  // test's records.
  @BeforeEach
  void startGroupAtLogEnd() throws Exception {
    LOG.info("seed={} (rerun with -Dengine.it.seed={})", seed, seed);
    Map<TopicPartition, OffsetAndMetadata> start =
        logEnd().entrySet().stream()
            .collect(toMap(Map.Entry::getKey, e -> new OffsetAndMetadata(e.getValue())));
    admin.alterConsumerGroupOffsets(group, start).all().get();
    start.forEach((tp, offset) -> startOffsets.put(tp.partition(), offset.offset()));
  }

  @AfterEach
  void stopLoadsAndInstances() {
    loads.forEach(Load::cancel);
    instances.forEach(i -> i.jobs.openGate());
    instances.forEach(i -> i.engine.stop());
  }

  @Test
  void underLoadEveryRecordCompletesOncePerPrInOrderAndCommitsReachTheLogEnd() throws Exception {
    var random = new Random(seed);
    var a = start("A", 20, 25);
    var inputs = produce(600, () -> 1 + random.nextInt(40));

    awaitCompletedAndCommitted(inputs);

    var executions = log.executions();
    assertThat(ExecutionLog.samePrOverlaps(executions)).as("I2").isEmpty();
    assertThat(ExecutionLog.offsetOrderViolations(executions)).as("per-PR order").isEmpty();
    assertThat(log.completions()).as("I5: one terminal outcome each").hasSize(inputs.size());
    assertStopsClean(a);
  }

  @Test
  void aSlowStubPausesIntakeWithoutARebalanceThenResumesAndDrains() throws Exception {
    var a = start("A", 0, 0);
    a.jobs.hold(id -> true);
    var nextPr = new AtomicInteger();
    var inputs = produce(300, () -> 1 + nextPr.getAndIncrement());

    await().atMost(DEADLINE).until(() -> a.metrics.pauseStates.contains("BACKPRESSURE:true"));
    int delivered = a.probe.recordsDelivered();
    int rebalances = a.probe.rebalanceCallbacks();
    assertThat(delivered)
        .as("intake stops once fewer than max.poll.records permits are left")
        .isGreaterThan(SETTINGS.inFlightCap() - SETTINGS.maxPollRecords())
        .isLessThanOrEqualTo(SETTINGS.inFlightCap());

    // The stall outlasts max.poll.interval: a paused consumer keeps polling, so the group keeps it.
    await()
        .during(SETTINGS.maxPollInterval().plusSeconds(2))
        .atMost(SETTINGS.maxPollInterval().plusSeconds(10))
        .until(
            () ->
                a.probe.recordsDelivered() == delivered
                    && a.probe.rebalanceCallbacks() == rebalances);

    a.jobs.openGate();
    awaitCompletedAndCommitted(inputs);
    assertThat(a.metrics.pauseStates).last().isEqualTo("BACKPRESSURE:false");
    assertThat(a.probe.rebalanceCallbacks()).as("no unexpected rebalance").isEqualTo(rebalances);
    assertStopsClean(a);
  }

  @Test
  void aSecondInstanceJoiningMidLoadKeepsEveryPrSerialAcrossBoth() throws Exception {
    var a = start("A", 30, 0);
    var load = startLoad(40);
    await().atMost(DEADLINE).until(() -> log.completionsBy("A") >= 300);

    // A's jobs in flight when the handover starts take 1.5-2.25 s, inside the 3 s grace; jobs after
    // that are fast again. The grace (I7) lets them finish so the handover commit covers them: B
    // then redoes nothing. Without the grace the commit lands below them and B runs them again.
    a.jobs.slowDown(Duration.ofMillis(1500));
    a.probe.onRevocationStart(a.jobs::speedUp);
    var b = start("B", 30, 0);
    await().atMost(DEADLINE).until(() -> log.completionsBy("B") >= 300);
    var inputs = load.stop();

    awaitCompletedAndCommitted(inputs);

    assertThat(a.probe.handedOver()).as("A handed partitions over").isNotEmpty();
    assertThat(redoneByB()).as("I7: in-flight work finished inside the grace and was committed").isEmpty();
    assertThat(ExecutionLog.samePrOverlaps(log.executions())).as("I2 across instances").isEmpty();
    assertStopsClean(a);
    assertStopsClean(b);
  }

  @Test
  void aZombieFinishingAfterTheHandoverReturnsItsPermitAndIsRedoneByTheNewOwner()
      throws Exception {
    var a = start("A", 30, 0);
    var load = startLoad(40);
    await().atMost(DEADLINE).until(() -> log.completionsBy("A") >= 300);

    // A's jobs from here on outlive the revocation grace, so those on handed-over partitions finish
    // after unregister: zombies (I7). Same-PR overlap with B is expected here and is E5's lock's job,
    // so this scenario asserts permits and commits, not I2.
    a.jobs.hold(id -> true);
    var b = start("B", 30, 0);
    await().atMost(DEADLINE).until(() -> !zombiesParked(a).isEmpty());
    var zombies = zombiesParked(a);
    a.jobs.openGate();
    await().atMost(DEADLINE).until(() -> completedBy("A").containsAll(zombies));
    var inputs = load.stop();

    awaitCompletedAndCommitted(inputs);

    assertThat(completedBy("B"))
        .as("B started from the handover commit, below the parked zombies, so it redoes them")
        .containsAll(zombies);
    assertStopsClean(a);
    assertStopsClean(b);
  }

  @Test
  void aBurstForOnePrInOnePollWindowRunsOnceAndSupersedesTheRest() throws Exception {
    int burstSize = 8;
    var a = start("A", 0, 0);
    // Held before the blocker exists, so it cannot run before the burst is queued behind it.
    a.jobs.hold(id -> true);
    var blocker = produce(1, () -> 7).getFirst();
    await().atMost(DEADLINE).until(() -> a.jobs.hasStarted(blocker));

    var burst = produce(burstSize, () -> 7);
    await().atMost(DEADLINE).until(() -> a.probe.recordsDelivered() >= 1 + burstSize);
    int pollsSoFar = a.probe.pollCalls();
    // The poller routes a whole batch before it polls again, so the next poll proves the burst
    // sits in the PR queue behind the held blocker.
    await().atMost(DEADLINE).until(() -> a.probe.pollCalls() > pollsSoFar);
    a.jobs.openGate();

    var inputs = new ArrayList<>(burst);
    inputs.add(blocker);
    awaitCompletedAndCommitted(inputs);

    var executedFromBurst =
        log.executions().stream().map(Execution::record).filter(burst::contains).toList();
    var superseded =
        log.completions().stream()
            .filter(c -> c.outcome().equals(CoalescePlan.SUPERSEDED_OUTCOME))
            .map(Completion::record)
            .toList();
    assertThat(executedFromBurst).containsExactly(burst.getLast());
    assertThat(superseded).containsExactlyInAnyOrderElementsOf(burst.subList(0, burstSize - 1));
    assertStopsClean(a);
  }

  @Test
  void anAbruptlyLostInstanceLeavesNoOffsetBehind() throws Exception {
    var a = start("A", 30, 0);
    var b = start("B", 30, 0);
    var load = startLoad(40);
    await().atMost(DEADLINE).until(() -> log.completionsBy("A") >= 100);

    // One held record pins A's watermark; A completing later offsets of that partition leaves a
    // done-but-uncommitted range behind at the crash (I3).
    var gap = new AtomicReference<RecordId>();
    a.jobs.hold(id -> gap.compareAndSet(null, id));
    await().atMost(DEADLINE).until(() -> gap.get() != null && completedAbove("A", gap.get()));
    a.jobs.kill();
    a.probe.crashOnNextPoll();
    await().atMost(DEADLINE).until(() -> !a.metrics.pollerDeaths.isEmpty());
    var inputs = load.stop();

    awaitCompletedAndCommitted(inputs);

    assertThat(log.completed()).as("completion set ⊇ input set").containsAll(inputs);
    assertThat(instancesThatCompleted(gap.get()))
        .as("the record A held at the crash")
        .containsOnly("B");
    assertThat(redoneByB()).as("A's done-but-uncommitted records, redone by B").isNotEmpty();
    assertThat(ExecutionLog.samePrOverlaps(log.executions())).as("I2 across instances").isEmpty();
    assertStopsClean(b);
    logReExecutions();
  }

  // I4 and I5 at the end: stopping waits for every permit (a leak expires the shutdown grace), and
  // no contained fault was reported along the way.
  private static void assertStopsClean(Instance instance) {
    // Equality, not "at least": a double release would raise the cap and move the I6 thresholds.
    await().atMost(DEADLINE).until(() -> instance.engine.availablePermits() >= SETTINGS.inFlightCap());
    assertThat(instance.engine.availablePermits())
        .as("I4: permits back to exactly the cap")
        .isEqualTo(SETTINGS.inFlightCap());
    instance.engine.stop();
    var metrics = instance.metrics;
    assertThat(metrics.expiredShutdownGraces).isEmpty();
    assertThat(metrics.pollerDeaths).isEmpty();
    assertThat(metrics.markCompleteFailures).isEmpty();
    assertThat(metrics.outcomeHandlerFailures).isEmpty();
    assertThat(metrics.closedPartitionRoutes).isEmpty();
  }

  private static Set<RecordId> zombiesParked(Instance instance) {
    var handedOver = instance.probe.handedOver();
    return instance.jobs.parked().stream()
        .filter(id -> handedOver.contains(partitionOf(id)))
        .collect(toSet());
  }

  private boolean completedAbove(String instance, RecordId held) {
    return completedBy(instance).stream()
        .anyMatch(id -> id.partition() == held.partition() && id.offset() > held.offset());
  }

  private Set<String> instancesThatCompleted(RecordId id) {
    return log.completions().stream()
        .filter(c -> c.record().equals(id))
        .map(Completion::instance)
        .collect(toSet());
  }

  private Set<RecordId> redoneByB() {
    var byA = completedBy("A");
    return completedBy("B").stream().filter(byA::contains).collect(toSet());
  }

  private Set<RecordId> completedBy(String instance) {
    return log.completions().stream()
        .filter(c -> c.instance().equals(instance))
        .map(Completion::record)
        .collect(toSet());
  }

  private static TopicPartition partitionOf(RecordId id) {
    return new TopicPartition(Topics.REVIEW_REQUESTED, id.partition());
  }

  private void awaitCompletedAndCommitted(List<RecordId> inputs) {
    await().atMost(DEADLINE).until(() -> log.completed().containsAll(inputs));
    await().atMost(DEADLINE).until(this::committedAtLogEnd);
  }

  // I3 at the end of a run: the group's committed offset is the next record to read, so a fully
  // processed partition commits exactly its log end (= last offset + 1).
  private boolean committedAtLogEnd() throws Exception {
    var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
    var end = logEnd();
    return end.entrySet().stream()
        .allMatch(
            e ->
                committed.containsKey(e.getKey())
                    && committed.get(e.getKey()).offset() == e.getValue());
  }

  private void logReExecutions() {
    long repeated = log.completionCounts().values().stream().filter(n -> n > 1).count();
    LOG.info("seed={}: {} offsets completed more than once (at-least-once)", seed, repeated);
  }

  private static Map<TopicPartition, Long> logEnd() throws Exception {
    Map<TopicPartition, OffsetSpec> latest =
        IntStream.range(0, Topics.REVIEW_REQUESTED_PARTITIONS)
            .mapToObj(p -> new TopicPartition(Topics.REVIEW_REQUESTED, p))
            .collect(toMap(tp -> tp, tp -> OffsetSpec.latest()));
    return admin.listOffsets(latest).all().get().entrySet().stream()
        .collect(toMap(Map.Entry::getKey, e -> e.getValue().offset()));
  }

  private static List<RecordId> produce(int count, IntSupplier prNumber)
      throws InterruptedException, ExecutionException {
    var sent = new ArrayList<Future<RecordMetadata>>();
    for (int i = 0; i < count; i++) {
      int pr = prNumber.getAsInt();
      sent.add(
          producer.send(
              new ProducerRecord<>(
                  Topics.REVIEW_REQUESTED,
                  new PrKey(12345, "acme/app", pr).render(),
                  envelope(pr, SEQUENCE.incrementAndGet()))));
    }
    var ids = new ArrayList<RecordId>();
    for (var future : sent) {
      var metadata = future.get();
      ids.add(new RecordId(metadata.partition(), metadata.offset()));
    }
    return ids;
  }

  // Later events carry later times, so the coalescer's survivor is the newest record.
  private static byte[] envelope(int pr, long sequence) {
    return EnvelopeSerde.toBytes(
        new ReviewRequestedEnvelope(
            ReviewRequestedEnvelope.SCHEMA_VERSION,
            "event-" + sequence,
            "delivery-" + sequence,
            EventType.REVIEW_REQUESTED,
            T0.plusMillis(sequence),
            12345L,
            "acme/app",
            pr,
            "sha-" + sequence,
            false,
            false));
  }

  private static String bootstrap() {
    return KAFKA.getBootstrapServers();
  }

  private Instance start(String name, int maxLatencyMillis, int failOneIn) {
    var instance = new Instance(name, maxLatencyMillis, failOneIn);
    instances.add(instance);
    instance.engine.start();
    return instance;
  }

  private Load startLoad(int prs) {
    var load = new Load(new Random(seed), prs);
    loads.add(load);
    return load;
  }

  private final class Instance {
    final StubJobExecutor jobs;
    final ConsumerProbe probe;
    final RecordingEngineMetrics metrics = new RecordingEngineMetrics();
    final Engine engine;

    Instance(String name, int maxLatencyMillis, int failOneIn) {
      Map<String, Object> config =
          new HashMap<>(ConsumerSettings.forReviewWorker(bootstrap(), SETTINGS));
      config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
      config.put(ConsumerConfig.CLIENT_ID_CONFIG, group + "-" + name);
      jobs =
          new StubJobExecutor(
              name, seed, startOffsets, maxLatencyMillis, failOneIn, log);
      probe = new ConsumerProbe(new KafkaConsumer<>(config));
      engine =
          new Engine(
              probe.consumer(),
              jobs,
              log.handlerFor(name),
              metrics,
              SETTINGS,
              () -> {},
              Executors.newVirtualThreadPerTaskExecutor());
    }
  }

  /** Produces a few seeded records every 20 ms until stopped: load that is still arriving. */
  private static final class Load {
    private final Set<RecordId> produced = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

    Load(Random random, int prs) {
      timer.scheduleWithFixedDelay(
          () -> {
            try {
              produced.addAll(produce(10, () -> 1 + random.nextInt(prs)));
            } catch (Exception e) {
              failure.compareAndSet(null, e);
            }
          },
          0,
          20,
          TimeUnit.MILLISECONDS);
    }

    List<RecordId> stop() throws InterruptedException {
      timer.shutdown();
      assertThat(timer.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      assertThat(failure.get()).as("load producer").isNull();
      return List.copyOf(produced);
    }

    void cancel() {
      timer.shutdownNow();
    }
  }
}
