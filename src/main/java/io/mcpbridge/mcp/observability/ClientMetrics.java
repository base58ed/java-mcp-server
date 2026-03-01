package io.mcpbridge.mcp.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/// Outbound HTTP and resilience metrics.
///
/// Low cardinality by design: `path` has ~3 values (API endpoints),
/// `outcome` is `success`/`error`, `to_state` is `open`/`half_open`/`closed`.
/// When telemetry is disabled, Meter is noop — all recording calls are zero-cost.
public final class ClientMetrics {

  private static final AttributeKey<String> PATH = AttributeKey.stringKey("http.path");
  private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
  private static final AttributeKey<String> TO_STATE = AttributeKey.stringKey("to_state");

  private final LongCounter httpRequests;
  private final DoubleHistogram httpDuration;
  private final LongCounter retries;
  private final LongCounter cbTransitions;

  public ClientMetrics(Meter meter) {
    httpRequests = meter.counterBuilder("http.client.requests")
        .setDescription("Outbound HTTP requests")
        .setUnit("{requests}")
        .build();

    httpDuration = meter.histogramBuilder("http.client.duration")
        .setDescription("Outbound HTTP request duration")
        .setUnit("s")
        .build();

    retries = meter.counterBuilder("resilience.retries")
        .setDescription("Resilience retry attempts")
        .setUnit("{retries}")
        .build();

    cbTransitions = meter.counterBuilder("resilience.circuit_breaker.transitions")
        .setDescription("Circuit breaker state transitions")
        .setUnit("{transitions}")
        .build();
  }

  public void recordRequest(String path, String outcome) {
    httpRequests.add(1, Attributes.of(PATH, path, OUTCOME, outcome));
  }

  public void recordDuration(String path, double seconds) {
    httpDuration.record(seconds, Attributes.of(PATH, path));
  }

  public void recordRetry() {
    retries.add(1);
  }

  public void recordCircuitBreakerTransition(String toState) {
    cbTransitions.add(1, Attributes.of(TO_STATE, toState));
  }
}
