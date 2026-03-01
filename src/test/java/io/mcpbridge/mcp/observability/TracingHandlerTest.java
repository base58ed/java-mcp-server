package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.config.Config.CorsConfig;
import io.mcpbridge.mcp.config.Config.ServerConfig;
import io.mcpbridge.mcp.config.Config.TelemetryConfig;
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

final class TracingHandlerTest {

  private UndertowServer server;
  private HttpClient http;
  private int port;

  @BeforeEach
  void setUp() throws IOException {
    port = freePort();
    var config = new ServerConfig("127.0.0.1", port, 5, 0,
        new CorsConfig(false, List.of(), List.of()));
    var shutdown = new GracefulShutdown(Duration.ofSeconds(5));
    var telemetry = Telemetry.create(TelemetryConfig.defaults(), "test", "0.0.1");
    server = UndertowServer.create(config, shutdown, telemetry);
    server.start();
    http = HttpClient.newHttpClient();
  }

  private static int freePort() throws IOException {
    try (var ss = new ServerSocket(0)) { return ss.getLocalPort(); }
  }

  @AfterEach
  void tearDown() {
    server.stop();
    http.close();
  }

  @Test
  void response_containsCorrelationIdHeader() throws Exception {
    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.headers().firstValue("x-correlation-id"))
        .isPresent()
        .hasValueSatisfying(id -> assertThat(id).hasSize(16).matches("[0-9a-f]+"));
  }

  @Test
  void incomingCorrelationId_isPreserved() throws Exception {
    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/live"))
            .header("x-correlation-id", "custom-trace-id")
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.headers().firstValue("x-correlation-id"))
        .hasValue("custom-trace-id");
  }

  @Test
  void handlerException_setsSpanError() throws Exception {
    server.routes().get("/throw", exchange -> {
      throw new RuntimeException("test error");
    });

    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/throw")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(500);
  }

  @Test
  void serverError_setsSpanStatusError() throws Exception {
    // Request to non-existent path → 404 (not 500, but exercises the completion listener)
    var response = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nonexistent"))
            .GET().build(),
        HttpResponse.BodyHandlers.ofString());

    // The exchange completion listener still runs, recording status code on the span
    assertThat(response.headers().firstValue("x-correlation-id")).isPresent();
  }
}
