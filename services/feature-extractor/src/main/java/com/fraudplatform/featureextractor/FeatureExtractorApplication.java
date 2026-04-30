package com.fraudplatform.featureextractor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the feature-extractor service.
 *
 * <p>Spring Boot is used here only for process lifecycle, configuration binding, and Actuator
 * metrics. The Kafka Streams topology itself is plain Kafka Streams — see
 * {@link com.fraudplatform.featureextractor.topology.FeatureExtractorTopology}.
 *
 * <p>Design decisions informing this service live in:
 *
 * <ul>
 *   <li>ADR-0002 — why Kafka Streams over Flink
 *   <li>ADR-0004 — event-time windowing with grace period (this PR)
 * </ul>
 */
@SpringBootApplication
public class FeatureExtractorApplication {

  public static void main(String[] args) {
    SpringApplication.run(FeatureExtractorApplication.class, args);
  }
}
