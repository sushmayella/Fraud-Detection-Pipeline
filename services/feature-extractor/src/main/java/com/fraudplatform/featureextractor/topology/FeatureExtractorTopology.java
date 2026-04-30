package com.fraudplatform.featureextractor.topology;

import com.fraudplatform.events.EnrichedTransaction;
import com.fraudplatform.events.Features;
import com.fraudplatform.events.Transaction;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the Kafka Streams topology for feature extraction.
 *
 * <p>This initial PR implements velocity features only — the number of transactions for a given
 * card in the last 1 minute and 1 hour windows. Baseline (z-score), geo delta, and device novelty
 * are deferred to follow-up PRs so each can land with its own ADR and test coverage.
 *
 * <h2>Topology shape</h2>
 *
 * <pre>
 *   raw-transactions (keyed by transactionId)
 *        │
 *        ▼  rekey to cardFingerprint — triggers a repartition
 *   ┌─────────────────────────────────┐
 *   │ count windowed(1m) → velocity1m │   state store: velocity-1m-store
 *   │ count windowed(1h) → velocity1h │   state store: velocity-1h-store
 *   └─────────────────────────────────┘
 *        │
 *        ▼  merge velocities back onto the original transaction
 *   enriched-transactions (keyed by transactionId)
 * </pre>
 *
 * <h2>Key design decisions (see ADR-0004)</h2>
 *
 * <ul>
 *   <li><b>Event-time windowing</b>. Windows are evaluated using the transaction's own
 *       {@code timestamp} field, not the wall clock at processing time. A transaction that
 *       occurred 5 seconds ago but arrives late still counts toward peers in the same window.
 *       This is what fraud detection actually wants.
 *   <li><b>Grace period of 5 seconds</b>. Late events within 5s of a window's close are still
 *       counted; past that, they're dropped. Balances correctness vs unbounded state.
 *   <li><b>Repartitioning on cardFingerprint</b>. The source topic is keyed by {@code
 *       transactionId} (for ordering per-txn across retries). Velocity counting requires
 *       co-locating all events for the same card on the same partition, which means a rekey.
 *       Kafka Streams handles this transparently by writing to an internal repartition topic.
 *   <li><b>Tumbling windows, not sliding or hopping</b>. A transaction landing at t=59s and
 *       the next at t=61s end up in different 1-minute tumbling windows. This is a deliberate
 *       simplification — sliding windows with advance=1s would give more precise velocity at
 *       much higher state-store cost. Tumbling is "close enough" for fraud signals.
 * </ul>
 *
 * <h2>Why a custom Processor for the final join</h2>
 *
 * <p>The natural way to "merge velocities into the transaction" is a stream-stream or
 * stream-table join. But we actually want to look up the current velocity counts for a specific
 * card at the moment we process its transaction — that's a point read, not a join. Using a
 * custom {@link Processor} with direct state-store access is simpler, faster, and avoids an
 * unnecessary repartition on the enriched output.
 */
public final class FeatureExtractorTopology {

  private static final Logger log = LoggerFactory.getLogger(FeatureExtractorTopology.class);

  public static final String SOURCE_TOPIC = "raw-transactions";
  public static final String SINK_TOPIC = "enriched-transactions";

  static final String VELOCITY_1M_STORE = "velocity-1m-store";
  static final String VELOCITY_1H_STORE = "velocity-1h-store";

  static final Duration WINDOW_1M = Duration.ofMinutes(1);
  static final Duration WINDOW_1H = Duration.ofHours(1);
  static final Duration GRACE = Duration.ofSeconds(5);

  private final Serde<Transaction> transactionSerde;
  private final Serde<EnrichedTransaction> enrichedSerde;

  public FeatureExtractorTopology(
      Serde<Transaction> transactionSerde, Serde<EnrichedTransaction> enrichedSerde) {
    this.transactionSerde = transactionSerde;
    this.enrichedSerde = enrichedSerde;
  }

  public Topology build() {
    StreamsBuilder builder = new StreamsBuilder();

    // Consume raw transactions. The source is keyed by transactionId; we immediately rekey.
    KStream<String, Transaction> byTransactionId =
        builder.stream(SOURCE_TOPIC, Consumed.with(Serdes.String(), transactionSerde));

    KStream<String, Transaction> byCard =
        byTransactionId
            .selectKey(
                (txnId, txn) -> txn.getCardFingerprint().toString(),
                org.apache.kafka.streams.kstream.Named.as("rekey-by-card"))
            .repartition(
                Repartitioned.<String, Transaction>with(Serdes.String(), transactionSerde)
                    .withName("by-card"));

    // Two windowed counts. Each call to count() materializes a WindowStore that we can look up
    // by (card, window) key. We keep the state stores around after counting so the enrichment
    // processor can read them.
    byCard
        .groupByKey(Grouped.with(Serdes.String(), transactionSerde))
        .windowedBy(TimeWindows.ofSizeAndGrace(WINDOW_1M, GRACE))
        .count(Materialized.<String, Long, WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(VELOCITY_1M_STORE));

    byCard
        .groupByKey(Grouped.with(Serdes.String(), transactionSerde))
        .windowedBy(TimeWindows.ofSizeAndGrace(WINDOW_1H, GRACE))
        .count(Materialized.<String, Long, WindowStore<org.apache.kafka.common.utils.Bytes, byte[]>>as(VELOCITY_1H_STORE));

    // Enrichment pass: for each transaction, read the current window counts and emit
    // the EnrichedTransaction. We re-key back to transactionId on the way out so downstream
    // consumers (rule engine, ml scorer) retain the original ordering guarantee.
    KStream<String, EnrichedTransaction> enriched =
        byCard
            .process(EnrichmentProcessor::new, VELOCITY_1M_STORE, VELOCITY_1H_STORE)
            .selectKey(
                (_k, enrichedTxn) -> enrichedTxn.getTransaction().getTransactionId().toString(),
                org.apache.kafka.streams.kstream.Named.as("rekey-by-txn-id"));

    enriched.to(SINK_TOPIC, Produced.with(Serdes.String(), enrichedSerde));

    return builder.build();
  }

  /**
   * Reads current velocity counts from the two windowed state stores and emits an {@link
   * EnrichedTransaction}.
   *
   * <p>Why a Processor instead of a join: velocity counts are point reads keyed by
   * (card, current_window). We don't want a windowed stream-stream join — we want "fetch the
   * current count for this card's current window at the moment I process this event."
   */
  static final class EnrichmentProcessor implements Processor<String, Transaction, String, EnrichedTransaction> {

    private ProcessorContext<String, EnrichedTransaction> context;
    private WindowStore<String, Long> velocity1mStore;
    private WindowStore<String, Long> velocity1hStore;

    @Override
    @SuppressWarnings("unchecked")
    public void init(ProcessorContext<String, EnrichedTransaction> context) {
      this.context = context;
      this.velocity1mStore = (WindowStore<String, Long>) context.getStateStore(VELOCITY_1M_STORE);
      this.velocity1hStore = (WindowStore<String, Long>) context.getStateStore(VELOCITY_1H_STORE);
    }

    @Override
    public void process(Record<String, Transaction> record) {
      String card = record.key();
      Transaction txn = record.value();
      Instant eventTime = Instant.ofEpochMilli(record.timestamp());

      int v1m = lookupCount(velocity1mStore, card, eventTime, WINDOW_1M);
      int v1h = lookupCount(velocity1hStore, card, eventTime, WINDOW_1H);

      Features features =
          Features.newBuilder()
              .setVelocity1m(v1m)
              .setVelocity1h(v1h)
              .setAmountZScore(null)
              .setGeoDeltaKm(null)
              .setIsNewDevice(null)
              .setMerchantCategoryRarity(null)
              .setUserAgeDays(null)
              .build();

      EnrichedTransaction enriched =
          EnrichedTransaction.newBuilder()
              .setTransaction(txn)
              .setFeatures(features)
              .setEnrichedAt(Instant.now())
              .build();

      if (log.isDebugEnabled()) {
        log.debug(
            "enriched txn={} card={} v1m={} v1h={}",
            txn.getTransactionId(),
            card,
            v1m,
            v1h);
      }

      context.forward(record.withValue(enriched));
    }

    /**
     * Looks up the count for the window containing {@code eventTime}.
     *
     * <p>Windows are aligned on wall-clock boundaries (e.g., a 1-minute window covers
     * [12:00:00, 12:01:00)). We sum all window-store entries that overlap {@code eventTime}.
     * For tumbling windows this is always exactly one entry, but the API returns an iterator
     * for generality.
     */
    private int lookupCount(
        WindowStore<String, Long> store, String card, Instant eventTime, Duration windowSize) {
      // The range [eventTime - windowSize + 1ms, eventTime] covers exactly the current window.
      Instant from = eventTime.minus(windowSize).plusMillis(1);
      Instant to = eventTime;
      try (var iter = store.fetch(card, from, to)) {
        long total = 0;
        while (iter.hasNext()) {
          total += iter.next().value;
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
      }
    }

    @Override
    public void close() {
      // No-op; state stores are managed by the runtime.
    }
  }
}
