package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.config.Config.CorsConfig;
import io.mcpbridge.mcp.config.Config.MetricsConfig;
import io.mcpbridge.mcp.config.Config.ServerConfig;
import io.mcpbridge.mcp.config.Config.TelemetryConfig;
import io.mcpbridge.mcp.config.Config.TracesConfig;
import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.mcpbridge.mcp.server.UndertowServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class MetricsHandlerTest {

  private UndertowServer server;
  private Telemetry telemetry;
  private HttpClient http;
  private int port;

  @BeforeEach
  void setUp() throws IOException {
    port = freePort();
    var telemetryConfig = new TelemetryConfig(true,
        new TracesConfig(false, "http://localhost:4318/v1/traces", 1.0),
        new MetricsConfig(true, "/metrics"));
    telemetry = Telemetry.create(telemetryConfig, "test-server", "0.0.1");

    var config = new ServerConfig("127.0.0.1", port, 5, 0,
        new CorsConfig(false, List.of(), List.of()));
    var shutdown = new GracefulShutdown(Duration.ofSeconds(5));
    server = UndertowServer.create(config, shutdown, telemetry);

    // Register metrics endpoint
    server.routes().get("/metrics", new MetricsHandler(telemetry.prometheusReader()));

    // Record some test metrics
    var metrics = new McpMetrics(telemetry.meter());
    metrics.recordCall("echo", "success");

    server.start();
    http = HttpClient.newHttpClient();
  }

  private static int freePort() throws IOException {
    try (var ss = new ServerSocket(0)) { return ss.getLocalPort(); }
  }

  @AfterEach
  void tearDown() {
    server.stop();
    telemetry.close();
    http.close();
  }

  @Test
  void metricsEndpoint_returns200WithPrometheusFormat() throws Exception {
    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(ct -> assertThat(ct).contains("text/plain"));
  }

  @Test
  void metricsEndpoint_containsRecordedMetrics() throws Exception {
    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.body()).contains("mcp_tool_calls");
  }
}
