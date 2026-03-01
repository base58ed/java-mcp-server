package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.config.Config.TelemetryConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// OTel SDK setup — real or noop based on TOML config.
///
/// When `telemetry.enabled = false`, returns noop instances (zero overhead).
/// When enabled, configures traces (OTLP HTTP) and/or metrics (Prometheus reader)
/// independently based on their sub-configs.
public final class Telemetry implements AutoCloseable {

  private static final Logger log = LogManager.getLogger();
  private static final String INSTRUMENTATION_NAME = "io.mcpbridge.mcp";

  private final OpenTelemetry otel;
  private final Tracer tracer;
  private final Meter meter;
  private final PrometheusMetricReader prometheusReader;
  private final OpenTelemetrySdk sdk; // null when noop

  private Telemetry(OpenTelemetry otel, Tracer tracer, Meter meter,
                    PrometheusMetricReader prometheusReader, OpenTelemetrySdk sdk) {
    this.otel = otel;
    this.tracer = tracer;
    this.meter = meter;
    this.prometheusReader = prometheusReader;
    this.sdk = sdk;
  }

  public OpenTelemetry otel() { return otel; }
  public Tracer tracer() { return tracer; }
  public Meter meter() { return meter; }
  public PrometheusMetricReader prometheusReader() { return prometheusReader; }

  public static Telemetry create(TelemetryConfig config, String serviceName, String serviceVersion) {
    if (!config.enabled()) {
      log.info("Telemetry disabled");
      var noop = OpenTelemetry.noop();
      return new Telemetry(noop,
          noop.getTracer(INSTRUMENTATION_NAME),
          noop.getMeter(INSTRUMENTATION_NAME),
          null, null);
    }

    var resource = Resource.getDefault().toBuilder()
        .put("service.name", serviceName)
        .put("service.version", serviceVersion)
        .build();

    // Traces
    SdkTracerProvider tracerProvider = null;
    if (config.traces().enabled()) {
      var exporter = OtlpHttpSpanExporter.builder()
          .setEndpoint(config.traces().endpoint())
          .build();
      var sampler = config.traces().samplerRatio() >= 1.0
          ? Sampler.alwaysOn()
          : Sampler.traceIdRatioBased(config.traces().samplerRatio());
      tracerProvider = SdkTracerProvider.builder()
          .setResource(resource)
          .setSampler(sampler)
          .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
          .build();
      log.info("Traces enabled → {}", config.traces().endpoint());
    }

    // Metrics
    PrometheusMetricReader prometheusReader = null;
    SdkMeterProvider meterProvider = null;
    if (config.metrics().enabled()) {
      prometheusReader = new PrometheusMetricReader(true, null);
      meterProvider = SdkMeterProvider.builder()
          .setResource(resource)
          .registerMetricReader(prometheusReader)
          .build();
      log.info("Metrics enabled → {}", config.metrics().path());
    }

    var sdkBuilder = OpenTelemetrySdk.builder();
    if (tracerProvider != null) { sdkBuilder.setTracerProvider(tracerProvider); }
    if (meterProvider != null) { sdkBuilder.setMeterProvider(meterProvider); }
    var sdk = sdkBuilder.build();

    return new Telemetry(sdk,
        sdk.getTracer(INSTRUMENTATION_NAME),
        sdk.getMeter(INSTRUMENTATION_NAME),
        prometheusReader, sdk);
  }

  @Override
  public void close() {
    if (sdk != null) {
      try {
        sdk.close();
        log.info("Telemetry shut down");
      } catch (Exception e) {
        log.warn("Telemetry shutdown failed", e);
      }
    }
  }
}
