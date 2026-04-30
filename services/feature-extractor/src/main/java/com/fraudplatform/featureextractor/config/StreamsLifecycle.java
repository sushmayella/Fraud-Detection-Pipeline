package com.fraudplatform.featureextractor.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Owns the {@link KafkaStreams} lifecycle.
 *
 * <p>Separate from {@link KafkaStreamsConfig} so tests can disable it (via Spring profile) and
 * exercise just the topology via {@code TopologyTestDriver} without connecting to a real broker.
 *
 * <p>Activated by default; disabled in the {@code test} profile so {@code @SpringBootTest} slices
 * don't fail trying to connect.
 */
@Component
@Profile("!test")
public class StreamsLifecycle {

  private static final Logger log = LoggerFactory.getLogger(StreamsLifecycle.class);

  private final KafkaStreams streams;

  public StreamsLifecycle(KafkaStreams streams) {
    this.streams = streams;
  }

  @PostConstruct
  public void start() {
    streams.setUncaughtExceptionHandler(
        ex -> {
          log.error("uncaught streams exception", ex);
          // SHUTDOWN_APPLICATION fails loudly so k8s / docker-compose restarts us cleanly
          // instead of limping along in a broken state.
          return org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
              .StreamThreadExceptionResponse.SHUTDOWN_APPLICATION;
        });
    log.info("starting Kafka Streams application");
    streams.start();
  }

  @PreDestroy
  public void stop() {
    log.info("stopping Kafka Streams application");
    streams.close();
  }
}
