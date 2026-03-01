package io.mcpbridge.mcp.observability;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatNoException;

final class McpMetricsTest {

  private final McpMetrics metrics = new McpMetrics(
      OpenTelemetry.noop().getMeter("test"));

  @Test
  void recordCall_noopMeter_doesNotThrow() {
    assertThatNoException().isThrownBy(() ->
        metrics.recordCall("getCurrentWeather", "success"));
  }

  @Test
  void recordDuration_noopMeter_doesNotThrow() {
    assertThatNoException().isThrownBy(() ->
        metrics.recordDuration("getCurrentWeather", 0.125));
  }

}
