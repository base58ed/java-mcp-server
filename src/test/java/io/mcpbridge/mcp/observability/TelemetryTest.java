package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.config.Config.MetricsConfig;
import io.mcpbridge.mcp.config.Config.TelemetryConfig;
import io.mcpbridge.mcp.config.Config.TracesConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TelemetryTest {

  @Test
  void disabled_returnsNoopInstances() {
    var telemetry = Telemetry.create(TelemetryConfig.defaults(), "test", "0.0.1");

    assertThat(telemetry.tracer()).isNotNull();
    assertThat(telemetry.meter()).isNotNull();
    assertThat(telemetry.prometheusReader()).isNull();

    telemetry.close(); // should not throw
  }

  @Test
  void metricsEnabled_createsPrometheusReader() {
    var config = new TelemetryConfig(true,
        new TracesConfig(false, "http://localhost:4318/v1/traces", 1.0),
        new MetricsConfig(true, "/metrics"));

    var telemetry = Telemetry.create(config, "test", "0.0.1");

    assertThat(telemetry.prometheusReader()).isNotNull();
    assertThat(telemetry.meter()).isNotNull();

    telemetry.close();
  }

  @Test
  void tracesEnabled_createsRealTracer() {
    var config = new TelemetryConfig(true,
        new TracesConfig(true, "http://localhost:4318/v1/traces", 0.5),
        new MetricsConfig(false, "/metrics"));

    var telemetry = Telemetry.create(config, "test", "0.0.1");

    assertThat(telemetry.tracer()).isNotNull();
    assertThat(telemetry.prometheusReader()).isNull();

    telemetry.close();
  }

  @Test
  void tracesWithAlwaysOnSampler_usesFullSampling() {
    var config = new TelemetryConfig(true,
        new TracesConfig(true, "http://localhost:4318/v1/traces", 1.0),
        new MetricsConfig(false, "/metrics"));

    var telemetry = Telemetry.create(config, "test", "0.0.1");

    assertThat(telemetry.otel()).isNotNull();

    telemetry.close();
  }
}
