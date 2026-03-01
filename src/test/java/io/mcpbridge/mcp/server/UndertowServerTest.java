package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.config.Config.CorsConfig;
import io.mcpbridge.mcp.config.Config.ServerConfig;
import io.mcpbridge.mcp.config.Config.TelemetryConfig;
import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.mcpbridge.mcp.observability.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

final class UndertowServerTest {

  private UndertowServer server;
  private HttpClient http;
  private int port;

  @BeforeEach
  void setUp() throws IOException {
    port = freePort();
    var config = new ServerConfig("127.0.0.1", port, 5, 0,
        new CorsConfig(true, List.of("*"), List.of("GET", "POST", "OPTIONS")));
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
    server.stop(100); // exercises the drain path — no in-flight requests so completes instantly
    http.close();
  }

  private HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> options(String path) throws IOException, InterruptedException {
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Nested
  class HealthEndpoints {

    @Test
    void liveness_returnsUp() throws Exception {
      var response = get("/health/live");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"UP\"");
    }

    @Test
    void readiness_returnsUp() throws Exception {
      var response = get("/health/ready");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"UP\"");
    }

    @Test
    void startup_returnsUp() throws Exception {
      var response = get("/health/started");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"UP\"");
    }

    @Test
    void readiness_failingCheck_returnsDown() throws Exception {
      server.healthHandler().addReadinessCheck("always-fail", () -> false);

      var response = get("/health/ready");
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(response.body()).contains("\"DOWN\"");
    }
  }

  @Nested
  class Cors {

    @Test
    void preflight_returns204WithCorsHeaders() throws Exception {
      var response = options("/health/live");

      assertThat(response.statusCode()).isEqualTo(204);
      assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
          .hasValue("*");
      assertThat(response.headers().firstValue("Access-Control-Allow-Methods"))
          .hasValue("GET, POST, OPTIONS");
    }

    @Test
    void normalRequest_includesCorsHeaders() throws Exception {
      var response = get("/health/live");

      assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
          .hasValue("*");
    }
  }

  @Nested
  class Routing {

    @Test
    void unknownPath_returns404() throws Exception {
      var response = get("/nonexistent");
      assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void serverAssignsEphemeralPort() {
      assertThat(port).isGreaterThan(0);
    }
  }
}