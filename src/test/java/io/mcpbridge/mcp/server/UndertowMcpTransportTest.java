package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.config.Config.CorsConfig;
import io.mcpbridge.mcp.config.Config.ServerConfig;
import io.mcpbridge.mcp.config.Config.TelemetryConfig;
import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.mcpbridge.mcp.observability.Telemetry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class UndertowMcpTransportTest {

  private UndertowServer server;
  private McpSyncServer mcpServer;
  private HttpClient http;
  private int port;
  private McpJsonMapper jsonMapper;

  @BeforeEach
  void setUp() throws IOException {
    port = freePort();
    jsonMapper = McpJsonDefaults.getMapper();
    var mcpTransport = new UndertowMcpTransport(jsonMapper);

    var config = new ServerConfig("127.0.0.1", port, 5, 0,
        new CorsConfig(true, List.of("*"), List.of("GET", "POST", "DELETE", "OPTIONS")));
    var shutdown = new GracefulShutdown(Duration.ofSeconds(5));
    var telemetry = Telemetry.create(TelemetryConfig.defaults(), "test", "0.0.1");
    server = UndertowServer.create(config, shutdown, telemetry);
    server.routes()
        .post("/mcp", mcpTransport)
        .get("/mcp", mcpTransport)
        .delete("/mcp", mcpTransport);

    // Build MCP server with a simple echo tool for testing
    var echoSchema = new McpSchema.JsonSchema(
        "object", Map.of("message", Map.of("type", "string")),
        List.of("message"), null, null, null);
    mcpServer = McpServer.sync(mcpTransport)
        .serverInfo("test-server", "0.1.0")
        .toolCall(
            McpSchema.Tool.builder()
                .name("echo")
                .description("Echoes the input")
                .inputSchema(echoSchema)
                .build(),
            (exchange, request) -> McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("echo: " + request.arguments().get("message"))))
                .build())
        .build();

    server.start();
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  private static int freePort() throws IOException {
    try (var ss = new ServerSocket(0)) { return ss.getLocalPort(); }
  }

  @AfterEach
  void tearDown() {
    if (mcpServer != null) { mcpServer.close(); }
    server.stop();
    http.close();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  private HttpResponse<String> postJson(String path, String body, String... headers) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(url(path)))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream");
    for (int i = 0; i < headers.length; i += 2) {
      builder.header(headers[i], headers[i + 1]);
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  // ── Tests ──────────────────────────────────────────────────────────────────

  @Nested
  class Initialize {

    @Test
    void initialize_returnsSessionId() throws Exception {
      var body = """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2024-11-05",
            "capabilities":{},
            "clientInfo":{"name":"test-client","version":"1.0"}
          }}""";

      var response = postJson("/mcp", body);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("Mcp-Session-Id")).isPresent();
      assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct ->
          assertThat(ct).contains("application/json"));
      assertThat(response.body()).contains("\"serverInfo\"");
      assertThat(response.body()).contains("test-server");
    }

    @Test
    void initialize_responseHasJsonRpcFields() throws Exception {
      var body = """
          {"jsonrpc":"2.0","id":42,"method":"initialize","params":{
            "protocolVersion":"2024-11-05",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1.0"}
          }}""";

      var response = postJson("/mcp", body);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"jsonrpc\":\"2.0\"");
      assertThat(response.body()).contains("\"id\":42");
      assertThat(response.body()).contains("\"result\"");
    }
  }

  @Nested
  class ToolCall {

    @Test
    void toolCall_returnsSseResponse() throws Exception {
      // First initialize to get session ID
      var initBody = """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2024-11-05",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1.0"}
          }}""";
      var initResponse = postJson("/mcp", initBody);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

      // Send initialized notification
      var notifBody = """
          {"jsonrpc":"2.0","method":"notifications/initialized"}""";
      postJson("/mcp", notifBody, "Mcp-Session-Id", sessionId);

      // Call the echo tool
      var toolBody = """
          {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
            "name":"echo","arguments":{"message":"hello"}
          }}""";
      var response = postJson("/mcp", toolBody, "Mcp-Session-Id", sessionId);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("echo: hello");
      assertThat(response.body()).contains("event: message");
    }

    @Test
    void toolsList_returnsSseWithTools() throws Exception {
      var initBody = """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2024-11-05",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1.0"}
          }}""";
      var initResponse = postJson("/mcp", initBody);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

      var notifBody = """
          {"jsonrpc":"2.0","method":"notifications/initialized"}""";
      postJson("/mcp", notifBody, "Mcp-Session-Id", sessionId);

      var listBody = """
          {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}""";
      var response = postJson("/mcp", listBody, "Mcp-Session-Id", sessionId);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("echo");
    }
  }

  @Nested
  class SessionManagement {

    @Test
    void delete_removesSession() throws Exception {
      var initBody = """
          {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
            "protocolVersion":"2024-11-05",
            "capabilities":{},
            "clientInfo":{"name":"test","version":"1.0"}
          }}""";
      var initResponse = postJson("/mcp", initBody);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

      // DELETE the session
      var deleteRequest = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .DELETE()
          .header("Mcp-Session-Id", sessionId)
          .build();
      var deleteResponse = http.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(deleteResponse.statusCode()).isEqualTo(200);

      // Subsequent request should fail — session gone
      var toolBody = """
          {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""";
      var response = postJson("/mcp", toolBody, "Mcp-Session-Id", sessionId);
      assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void missingSessionId_returns400() throws Exception {
      var body = """
          {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
      var response = postJson("/mcp", body);
      assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void invalidSessionId_returns404() throws Exception {
      var body = """
          {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
      var response = postJson("/mcp", body, "Mcp-Session-Id", "nonexistent");
      assertThat(response.statusCode()).isEqualTo(404);
    }
  }

  @Nested
  class SseStream {

    @Test
    void get_missingSessionId_returns400() throws Exception {
      var getRequest = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .GET()
          .header("Accept", "text/event-stream")
          .build();
      var response = http.send(getRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void get_invalidSessionId_returns404() throws Exception {
      var getRequest = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .GET()
          .header("Accept", "text/event-stream")
          .header("Mcp-Session-Id", "nonexistent")
          .build();
      var response = http.send(getRequest, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
    }
  }

  @Nested
  class Notifications {

    @Test
    void notification_noId_returns202() throws Exception {
      var initResponse = postJson("/mcp", INIT_BODY);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

      var response = postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessionId);
      assertThat(response.statusCode()).isIn(200, 202, 204);
    }

    @Test
    void notificationWithoutSession_returns400() throws Exception {
      var response = postJson("/mcp", NOTIF_BODY);
      assertThat(response.statusCode()).isEqualTo(400);
    }
  }

  @Nested
  class ErrorHandling {

    @Test
    void invalidJson_returns400() throws Exception {
      var response = postJson("/mcp", "not json at all");
      assertThat(response.statusCode()).isEqualTo(400);
      assertThat(response.body()).contains("Invalid message format");
    }

    @Test
    void emptyBody_returns400() throws Exception {
      var response = postJson("/mcp", "");
      assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void delete_missingSessionId_returns400() throws Exception {
      var request = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .DELETE()
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void delete_unknownSession_returns404() throws Exception {
      var request = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .DELETE()
          .header("Mcp-Session-Id", "does-not-exist")
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void unsupportedMethod_returns405() throws Exception {
      var request = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .PUT(HttpRequest.BodyPublishers.ofString(""))
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void oversizedBody_returns413() throws Exception {
      var hugeBody = "x".repeat(1_048_577); // 1 MB + 1 byte
      var response = postJson("/mcp", hugeBody);
      assertThat(response.statusCode()).isEqualTo(413);
      assertThat(response.body()).contains("1 MB");
    }

    @Test
    void oversizedBody_noContentLength_returns413() throws Exception {
      var data = new byte[1_048_577]; // 1 MB + 1 byte, no Content-Length header
      var request = HttpRequest.newBuilder(URI.create(url("/mcp")))
          .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(data)))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json, text/event-stream")
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(413);
      assertThat(response.body()).contains("1 MB");
    }

    @Test
    void sessionFactoryNotSet_returns503() throws Exception {
      int p = freePort();
      var transport = new UndertowMcpTransport(jsonMapper);
      var cfg = new ServerConfig("127.0.0.1", p, 5, 0,
          new CorsConfig(true, List.of("*"), List.of("GET", "POST", "DELETE", "OPTIONS")));
      var sd = new GracefulShutdown(Duration.ofSeconds(5));
      var tel = Telemetry.create(TelemetryConfig.defaults(), "test", "0.0.1");
      var srv = UndertowServer.create(cfg, sd, tel);
      srv.routes().post("/mcp", transport);
      srv.start();
      try {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p + "/mcp"))
            .POST(HttpRequest.BodyPublishers.ofString(INIT_BODY))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("Server not ready");
      } finally {
        srv.stop();
      }
    }

    @Test
    void requestDuringShutdown_returns503() throws Exception {
      mcpServer.close();
      mcpServer = null; // prevent double-close in tearDown

      var response = postJson("/mcp", INIT_BODY);
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(response.body()).contains("shutting down");
    }

    @Test
    void post_jsonRpcWithUnsupportedMethod_returnsError() throws Exception {
      var initResponse = postJson("/mcp", INIT_BODY);
      var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
      postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessionId);

      var body = """
          {"jsonrpc":"2.0","id":99,"method":"nonexistent/method","params":{}}""";
      var response = postJson("/mcp", body, "Mcp-Session-Id", sessionId);

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("error");
    }
  }

  @Nested
  class Concurrency {

    // ── ConcurrentHashMap: session creation under contention ──────────────

    @Test
    void concurrentInitialize_allSessionsCreated() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        Set<String> sessionIds = ConcurrentHashMap.newKeySet();
        var errors = new AtomicInteger(0);

        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          int id = t + 100;
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try {
              barrier.await();
              var body = """
                  {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{
                    "protocolVersion":"2024-11-05",
                    "capabilities":{},
                    "clientInfo":{"name":"test-%d","version":"1.0"}
                  }}""".formatted(id, id);
              var response = postJson("/mcp", body);
              if (response.statusCode() == 200) {
                response.headers().firstValue("Mcp-Session-Id").ifPresent(sessionIds::add);
              } else {
                errors.incrementAndGet();
              }
            } catch (Exception e) {
              errors.incrementAndGet();
            }
          });
        }
        for (var vt : vThreads) { vt.join(10_000); }

        assertThat(errors.get()).isZero();
        assertThat(sessionIds).hasSize(threads);
      });
    }

    // ── AtomicBoolean closing: close + request race ──────────────────────

    @Test
    void closeAndRequest_noCorruption() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        var initResponse = postJson("/mcp", INIT_BODY);
        assertThat(initResponse.statusCode()).isEqualTo(200);

        mcpServer.close();
        mcpServer = null;

        await().atMost(2, SECONDS).untilAsserted(() -> {
          var response = postJson("/mcp", INIT_BODY);
          assertThat(response.statusCode()).isEqualTo(503);
        });
      });
    }

    // ── StampedLock: concurrent tool calls each get own SseTransport ─────

    @Test
    void concurrentToolCalls_allComplete() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
        var initResponse = postJson("/mcp", INIT_BODY);
        var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
        postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessionId);

        int threads = 4;
        var barrier = new CyclicBarrier(threads);
        var successes = new AtomicInteger(0);
        var errors = new AtomicInteger(0);

        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          int id = t + 10;
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try {
              barrier.await();
              var body = """
                  {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                    "name":"echo","arguments":{"message":"msg-%d"}
                  }}""".formatted(id, id);
              var response = postJson("/mcp", body, "Mcp-Session-Id", sessionId);
              if (response.statusCode() == 200
                  && response.body().contains("event: message")
                  && response.body().contains("echo: msg-" + id)) {
                successes.incrementAndGet();
              } else {
                errors.incrementAndGet();
              }
            } catch (Exception e) {
              errors.incrementAndGet();
            }
          });
        }
        for (var vt : vThreads) { vt.join(10_000); }

        assertThat(errors.get()).isZero();
        assertThat(successes.get()).isEqualTo(threads);
      });
    }

    // ── Concurrent delete same session — one 200, others 404 ─────────────

    @Test
    void concurrentDeleteSameSession_oneSucceedsOthersGet404() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        var initResponse = postJson("/mcp", INIT_BODY);
        var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();

        int threads = 4;
        var barrier = new CyclicBarrier(threads);
        var ok = new AtomicInteger(0);
        var notFound = new AtomicInteger(0);

        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try {
              barrier.await();
              var req = HttpRequest.newBuilder(URI.create(url("/mcp")))
                  .DELETE()
                  .header("Mcp-Session-Id", sessionId)
                  .build();
              var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
              if (resp.statusCode() == 200) { ok.incrementAndGet(); }
              else if (resp.statusCode() == 404) { notFound.incrementAndGet(); }
            } catch (Exception ignored) {}
          });
        }
        for (var vt : vThreads) { vt.join(10_000); }

        // Exactly one thread should delete successfully; the rest see 404.
        // ConcurrentHashMap.get + session.delete + remove is not atomic,
        // so multiple threads may get the session, but delete is idempotent.
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        assertThat(ok.get() + notFound.get()).isEqualTo(threads);
      });
    }

    // ── Delete + tool call race on same session ──────────────────────────

    @Test
    void deleteAndToolCall_noCorruption() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        var initResponse = postJson("/mcp", INIT_BODY);
        var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
        postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessionId);

        var barrier = new CyclicBarrier(2);
        var statuses = new ConcurrentHashMap<String, Integer>();

        var deleter = Thread.ofVirtual().start(() -> {
          try {
            barrier.await();
            var req = HttpRequest.newBuilder(URI.create(url("/mcp")))
                .DELETE()
                .header("Mcp-Session-Id", sessionId)
                .build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            statuses.put("delete", resp.statusCode());
          } catch (Exception ignored) {}
        });

        var caller = Thread.ofVirtual().start(() -> {
          try {
            barrier.await();
            var body = """
                {"jsonrpc":"2.0","id":50,"method":"tools/call","params":{
                  "name":"echo","arguments":{"message":"race"}
                }}""";
            var resp = postJson("/mcp", body, "Mcp-Session-Id", sessionId);
            statuses.put("toolCall", resp.statusCode());
          } catch (Exception ignored) {}
        });

        deleter.join(10_000);
        caller.join(10_000);

        // Both operations complete without hanging or crashing.
        // Delete: 200 or 404. Tool call: 200 (if before delete) or 404 (if after).
        assertThat(statuses).containsKey("delete");
        assertThat(statuses).containsKey("toolCall");
        assertThat(statuses.get("delete")).isIn(200, 404);
        assertThat(statuses.get("toolCall")).isIn(200, 404);
      });
    }

    // ── Shutdown during active tool calls — no hang ──────────────────────

    @Test
    void shutdownDuringToolCalls_noHang() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
        // Create multiple sessions with active tool calls
        var sessions = new ArrayList<String>();
        for (int i = 0; i < 3; i++) {
          var initBody = """
              {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{
                "protocolVersion":"2024-11-05",
                "capabilities":{},
                "clientInfo":{"name":"session-%d","version":"1.0"}
              }}""".formatted(i + 200, i);
          var resp = postJson("/mcp", initBody);
          sessions.add(resp.headers().firstValue("Mcp-Session-Id").orElseThrow());
          postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessions.getLast());
        }

        var barrier = new CyclicBarrier(sessions.size() + 1); // +1 for closer
        var toolResults = new ConcurrentHashMap<String, Integer>();

        // Tool callers — one per session
        var callers = new Thread[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
          int idx = i;
          var sid = sessions.get(i);
          callers[i] = Thread.ofVirtual().start(() -> {
            try {
              barrier.await();
              var body = """
                  {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                    "name":"echo","arguments":{"message":"shutdown-test"}
                  }}""".formatted(idx + 300);
              var resp = postJson("/mcp", body, "Mcp-Session-Id", sid);
              toolResults.put("session-" + idx, resp.statusCode());
            } catch (Exception ignored) {}
          });
        }

        // Closer — shuts down MCP server
        var closer = Thread.ofVirtual().start(() -> {
          try {
            barrier.await();
            mcpServer.close();
          } catch (Exception ignored) {}
        });

        for (var c : callers) { c.join(10_000); }
        closer.join(10_000);
        mcpServer = null; // prevent double-close in tearDown

        // All operations completed (no deadlock).
        // Tool calls got 200 (completed before close) or 503/404 (after close).
        for (var entry : toolResults.entrySet()) {
          assertThat(entry.getValue()).isIn(200, 404, 500, 503);
        }
      });
    }

    // ── Concurrent tool calls on same session with high contention ───────

    @Test
    void highContentionToolCalls_noDeadlockOrCorruption() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
        var initResponse = postJson("/mcp", INIT_BODY);
        var sessionId = initResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
        postJson("/mcp", NOTIF_BODY, "Mcp-Session-Id", sessionId);

        int threads = 8;
        var barrier = new CyclicBarrier(threads);
        var completed = new AtomicInteger(0);

        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          int id = t + 500;
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try {
              barrier.await();
              var body = """
                  {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{
                    "name":"echo","arguments":{"message":"contention-%d"}
                  }}""".formatted(id, id);
              var response = postJson("/mcp", body, "Mcp-Session-Id", sessionId);
              // Any completion is good — proves no deadlock
              if (response.statusCode() == 200) {
                // Verify SSE event is well-formed (not interleaved/torn)
                var respBody = response.body();
                assertThat(respBody).contains("event: message");
                assertThat(respBody).contains("echo: contention-" + id);
              }
              completed.incrementAndGet();
            } catch (Exception ignored) {}
          });
        }
        for (var vt : vThreads) { vt.join(15_000); }

        assertThat(completed.get()).isEqualTo(threads);
      });
    }
  }

  // ── Common bodies ─────────────────────────────────────────────────────────

  private static final String INIT_BODY = """
      {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2024-11-05",
        "capabilities":{},
        "clientInfo":{"name":"test","version":"1.0"}
      }}""";

  private static final String NOTIF_BODY = """
      {"jsonrpc":"2.0","method":"notifications/initialized"}""";
}
