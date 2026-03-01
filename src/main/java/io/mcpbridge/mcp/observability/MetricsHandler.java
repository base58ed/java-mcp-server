package io.mcpbridge.mcp.observability;

import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.ByteArrayOutputStream;

/// Serves GET /metrics in Prometheus text format from Undertow.
///
/// Collects OTel SDK metrics via PrometheusMetricReader, formats as
/// Prometheus exposition text, and writes to the response.
public final class MetricsHandler implements HttpHandler {

  private final PrometheusMetricReader reader;
  private final PrometheusTextFormatWriter writer;

  public MetricsHandler(PrometheusMetricReader reader) {
    assert reader != null : "PrometheusMetricReader required";
    this.reader = reader;
    this.writer = PrometheusTextFormatWriter.create();
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    var snapshots = reader.collect();
    var buf = new ByteArrayOutputStream(4096);
    writer.write(buf, snapshots);

    exchange.setStatusCode(200);
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, writer.getContentType() + "; charset=utf-8");
    exchange.getResponseSender().send(buf.toString(java.nio.charset.StandardCharsets.UTF_8));
  }
}
