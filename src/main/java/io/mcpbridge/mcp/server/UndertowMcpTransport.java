package io.mcpbridge.mcp.server;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession.McpStreamableServerSessionInit;
import io.modelcontextprotocol.spec.McpStreamableServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

/// Undertow adapter for MCP Streamable HTTP transport (spec 2025-03-26+).
///
/// Bridges Undertow's `HttpHandler` with the MCP SDK's `McpStreamableServerTransportProvider`.
/// All logic is blocking (virtual threads); `Mono` appears only where the SDK interface
/// demands it — thin `Mono.fromRunnable()` wrappers around blocking code.
public final class UndertowMcpTransport implements McpStreamableServerTransportProvider, HttpHandler {

  private static final Logger log = LogManager.getLogger();

  private static final HttpString MCP_SESSION_ID = new HttpString("Mcp-Session-Id");
  private static final HttpString LAST_EVENT_ID_HEADER = new HttpString("Last-Event-ID");
  private static final HttpString CACHE_CONTROL = new HttpString("Cache-Control");
  private static final HttpString CONNECTION_HEADER = new HttpString("Connection");

  private static final String TEXT_EVENT_STREAM = "text/event-stream";
  private static final String APPLICATION_JSON = "application/json";
  private static final String SSE_MESSAGE_EVENT = "message";
  private static final int MAX_REQUEST_BODY = 1_048_576; // 1 MB
  private static final int MAX_REPLAY_EVENTS = 1000;

  private final McpJsonMapper jsonMapper;
  private final ConcurrentHashMap<String, McpStreamableServerSession> sessions = new ConcurrentHashMap<>();
  private volatile McpStreamableServerSession.Factory sessionFactory;
  private final AtomicBoolean closing = new AtomicBoolean();

  public UndertowMcpTransport(McpJsonMapper jsonMapper) {
    assert jsonMapper != null : "jsonMapper required";
    this.jsonMapper = jsonMapper;
  }

  // ── McpStreamableServerTransportProvider ───────────────────────────────────

  @Override
  public void setSessionFactory(McpStreamableServerSession.Factory sessionFactory) {
    this.sessionFactory = sessionFactory;
  }

  @Override
  public List<String> protocolVersions() {
    return List.of(
        ProtocolVersions.MCP_2024_11_05,
        ProtocolVersions.MCP_2025_03_26,
        ProtocolVersions.MCP_2025_06_18,
        ProtocolVersions.MCP_2025_11_25);
  }

  @Override
  public Mono<Void> notifyClients(String method, Object params) {
    if (sessions.isEmpty()) { return Mono.empty(); }
    return Mono.fromRunnable(() -> sessions.values().forEach(session -> {
      try {
        session.sendNotification(method, params).block(Duration.ofSeconds(5));
      } catch (Exception e) {
        log.error("Failed to notify session {}", session.getId(), e);
      }
    }));
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.fromRunnable(() -> {
      closing.set(true);
      for (var session : sessions.values()) {
        try {
          session.closeGracefully().block(Duration.ofSeconds(5));
        } catch (Exception e) {
          log.error("Failed to close session {}", session.getId(), e);
        }
      }
      sessions.clear();
    });
  }

  // ── HttpHandler ────────────────────────────────────────────────────────────

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    if (closing.get()) {
      exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
      exchange.getResponseSender().send("Server is shutting down");
      return;
    }

    var method = exchange.getRequestMethod();
    log.debug("{} /mcp from {}", method, exchange.getSourceAddress());
    if (Methods.POST.equals(method)) { handlePost(exchange); }
    else if (Methods.GET.equals(method)) { handleGet(exchange); }
    else if (Methods.DELETE.equals(method)) { handleDelete(exchange); }
    else {
      exchange.setStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
      exchange.endExchange();
    }
  }

  // ── POST /mcp ──────────────────────────────────────────────────────────────

  private void handlePost(HttpServerExchange exchange) throws Exception {
    exchange.startBlocking();

    // Reject oversized requests early via Content-Length header
    long contentLength = exchange.getRequestContentLength();
    if (contentLength > MAX_REQUEST_BODY) {
      log.warn("Rejected oversized request: Content-Length={} from {}", contentLength, exchange.getSourceAddress());
      exchange.setStatusCode(StatusCodes.REQUEST_ENTITY_TOO_LARGE);
      exchange.getResponseSender().send("Request body exceeds 1 MB limit");
      return;
    }

    var bytes = exchange.getInputStream().readNBytes(MAX_REQUEST_BODY + 1);
    if (bytes.length > MAX_REQUEST_BODY) {
      log.warn("Rejected oversized request: read {} bytes from {}", bytes.length, exchange.getSourceAddress());
      exchange.setStatusCode(StatusCodes.REQUEST_ENTITY_TOO_LARGE);
      exchange.getResponseSender().send("Request body exceeds 1 MB limit");
      return;
    }
    var body = new String(bytes, StandardCharsets.UTF_8);
    log.debug("POST /mcp body: {}", body);

    McpSchema.JSONRPCMessage message;
    try {
      message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
    } catch (Exception e) {
      log.warn("Failed to parse JSON-RPC message: {}", e.getMessage());
      sendJsonError(exchange, StatusCodes.BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid message format: " + e.getMessage());
      return;
    }

    switch (message) {
      case McpSchema.JSONRPCRequest req
          when McpSchema.METHOD_INITIALIZE.equals(req.method()) -> handleInit(exchange, req);
      case McpSchema.JSONRPCRequest req -> {
        log.debug("POST request: method={}, id={}", req.method(), req.id());
        handleRequest(exchange, req);
      }
      case McpSchema.JSONRPCNotification notif -> {
        log.debug("POST notification: method={}", notif.method());
        handleNotification(exchange, notif);
      }
      case McpSchema.JSONRPCResponse resp -> handleClientResponse(exchange, resp);
    }
  }

  /// Initialize: JSON response with `Mcp-Session-Id` header.
  private void handleInit(HttpServerExchange exchange, McpSchema.JSONRPCRequest request) {
    if (sessionFactory == null) {
      log.error("Initialize request rejected: sessionFactory not set");
      sendJsonErrorSafe(exchange, StatusCodes.SERVICE_UNAVAILABLE,
          McpSchema.ErrorCodes.INTERNAL_ERROR, "Server not ready");
      return;
    }
    try {
      var initRequest = jsonMapper.convertValue(
          request.params(), new TypeRef<McpSchema.InitializeRequest>() {});
      McpStreamableServerSessionInit init = sessionFactory.startSession(initRequest);
      var session = init.session();
      sessions.put(session.getId(), session);

      var initResult = init.initResult().block();
      var response = new McpSchema.JSONRPCResponse(
          McpSchema.JSONRPC_VERSION, request.id(), initResult, null);

      exchange.getResponseHeaders()
          .put(Headers.CONTENT_TYPE, APPLICATION_JSON)
          .put(MCP_SESSION_ID, session.getId());
      exchange.setStatusCode(StatusCodes.OK);
      exchange.getResponseSender().send(jsonMapper.writeValueAsString(response));

      log.info("MCP session initialized: {}", session.getId());
    } catch (Exception e) {
      log.error("Failed to initialize MCP session", e);
      sendJsonErrorSafe(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
          McpSchema.ErrorCodes.INTERNAL_ERROR, "Failed to initialize: " + e.getMessage());
    }
  }

  /// Non-init request: SSE response stream. SDK sends result via transport, then closes it.
  private void handleRequest(HttpServerExchange exchange, McpSchema.JSONRPCRequest request) {
    var session = requireSession(exchange);
    if (session == null) { return; }

    exchange.getResponseHeaders()
        .put(Headers.CONTENT_TYPE, TEXT_EVENT_STREAM)
        .put(CACHE_CONTROL, "no-cache")
        .put(CONNECTION_HEADER, "keep-alive");
    exchange.setStatusCode(StatusCodes.OK);

    var transport = new SseTransport(exchange);
    try {
      session.responseStream(request, transport).block();
    } catch (Exception e) {
      log.error("Failed to handle request stream", e);
    }
  }

  /// Client notification: 202 Accepted, no body.
  private void handleNotification(HttpServerExchange exchange, McpSchema.JSONRPCNotification notification) {
    var session = requireSession(exchange);
    if (session == null) {
      log.warn("Notification {} rejected: no valid session", notification.method());
      return;
    }

    try {
      session.accept(notification).block();
      exchange.setStatusCode(StatusCodes.ACCEPTED);
      exchange.endExchange();
      log.debug("Notification accepted: {}", notification.method());
    } catch (Exception e) {
      log.error("Failed to handle notification {}", notification.method(), e);
      sendJsonErrorSafe(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
          McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
    }
  }

  /// Client response to server-initiated request: 202 Accepted, no body.
  private void handleClientResponse(HttpServerExchange exchange, McpSchema.JSONRPCResponse response) {
    var session = requireSession(exchange);
    if (session == null) { return; }

    try {
      session.accept(response).block();
      exchange.setStatusCode(StatusCodes.ACCEPTED);
      exchange.endExchange();
    } catch (Exception e) {
      log.error("Failed to handle client response", e);
      sendJsonErrorSafe(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
          McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
    }
  }

  // ── GET /mcp — long-lived SSE stream for server-initiated messages ─────────

  private void handleGet(HttpServerExchange exchange) {
    var sessionId = exchange.getRequestHeaders().getFirst(MCP_SESSION_ID);
    log.debug("GET /mcp session={}", sessionId);
    if (sessionId == null || sessionId.isBlank()) {
      sendJsonErrorSafe(exchange, StatusCodes.BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST, "Mcp-Session-Id header required");
      return;
    }

    var session = sessions.get(sessionId);
    if (session == null) {
      log.debug("GET /mcp session not found: {}", sessionId);
      exchange.setStatusCode(StatusCodes.NOT_FOUND);
      exchange.endExchange();
      return;
    }

    exchange.getResponseHeaders()
        .put(Headers.CONTENT_TYPE, TEXT_EVENT_STREAM)
        .put(CACHE_CONTROL, "no-cache")
        .put(CONNECTION_HEADER, "keep-alive");
    exchange.setStatusCode(StatusCodes.OK);

    var transport = new SseTransport(exchange);

    // Replay missed events if Last-Event-ID present
    var lastEventId = exchange.getRequestHeaders().getFirst(LAST_EVENT_ID_HEADER);
    if (lastEventId != null) {
      try {
        var replayed = session.replay(lastEventId).take(MAX_REPLAY_EVENTS).collectList().block(Duration.ofSeconds(5));
        if (replayed != null) {
          for (var msg : replayed) {
            transport.writeSseEvent(msg, null);
          }
        }
      } catch (Exception e) {
        log.error("Failed to replay messages", e);
        transport.close();
        return;
      }
    }

    // Register as listening stream — SDK pushes messages via transport.sendMessage()
    log.debug("GET /mcp SSE stream established for session {}", sessionId);
    session.listeningStream(transport);

    // Block this virtual thread until transport closes
    // (session delete, server shutdown, or client disconnect)
    transport.awaitClose();
    log.debug("GET /mcp SSE stream closed for session {}", sessionId);
  }

  // ── DELETE /mcp — session cleanup ──────────────────────────────────────────

  private void handleDelete(HttpServerExchange exchange) {
    var sessionId = exchange.getRequestHeaders().getFirst(MCP_SESSION_ID);
    if (sessionId == null || sessionId.isBlank()) {
      sendJsonErrorSafe(exchange, StatusCodes.BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST, "Mcp-Session-Id header required");
      return;
    }

    var session = sessions.get(sessionId);
    if (session == null) {
      exchange.setStatusCode(StatusCodes.NOT_FOUND);
      exchange.endExchange();
      return;
    }

    try {
      session.delete().block();
      sessions.remove(sessionId);
      exchange.setStatusCode(StatusCodes.OK);
      exchange.endExchange();
      log.info("MCP session deleted: {}", sessionId);
    } catch (Exception e) {
      log.error("Failed to delete session {}", sessionId, e);
      sendJsonErrorSafe(exchange, StatusCodes.INTERNAL_SERVER_ERROR,
          McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage());
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private McpStreamableServerSession requireSession(HttpServerExchange exchange) {
    var sessionId = exchange.getRequestHeaders().getFirst(MCP_SESSION_ID);
    if (sessionId == null || sessionId.isBlank()) {
      sendJsonErrorSafe(exchange, StatusCodes.BAD_REQUEST,
          McpSchema.ErrorCodes.INVALID_REQUEST, "Mcp-Session-Id header required");
      return null;
    }
    var session = sessions.get(sessionId);
    if (session == null) {
      sendJsonErrorSafe(exchange, StatusCodes.NOT_FOUND,
          McpSchema.ErrorCodes.INTERNAL_ERROR, "Session not found: " + sessionId);
      return null;
    }
    return session;
  }

  private void sendJsonError(HttpServerExchange exchange, int httpStatus, int errorCode, String message)
      throws IOException {
    var error = McpError.builder(errorCode).message(message).build();
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, APPLICATION_JSON);
    exchange.setStatusCode(httpStatus);
    exchange.getResponseSender().send(jsonMapper.writeValueAsString(error));
  }

  private void sendJsonErrorSafe(HttpServerExchange exchange, int httpStatus, int errorCode, String message) {
    try {
      sendJsonError(exchange, httpStatus, errorCode, message);
    } catch (IOException e) {
      log.error("Failed to send error response", e);
      exchange.setStatusCode(httpStatus);
      exchange.endExchange();
    }
  }

  // ── Per-request SSE transport ──────────────────────────────────────────────

  /// Writes SSE events to an Undertow exchange output stream.
  /// Thread-safe via `StampedLock` (avoids virtual thread pinning from `synchronized`).
  /// Non-reentrant — close is always deferred to after unlock.
  /// SDK interface requires `Mono` returns — each is a thin `Mono.fromRunnable` wrapper.
  private final class SseTransport implements McpStreamableServerTransport {

    private final OutputStream out;
    private final StampedLock lock = new StampedLock();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private boolean closed = false; // guarded by lock

    SseTransport(HttpServerExchange exchange) {
      exchange.startBlocking();
      this.out = exchange.getOutputStream();
      // Flush immediately to commit response headers to the client.
      // Undertow buffers headers until the first write or explicit flush.
      // Without this, SSE clients never receive the HTTP 200 response.
      try { out.flush(); } catch (IOException e) { close(); }
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
      return Mono.fromRunnable(() -> writeSseEvent(message, null));
    }

    @Override
    public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
      return Mono.fromRunnable(() -> writeSseEvent(message, messageId));
    }

    @Override
    public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
      return jsonMapper.convertValue(data, typeRef);
    }

    @Override
    public Mono<Void> closeGracefully() {
      return Mono.fromRunnable(this::close);
    }

    @Override
    public void close() {
      long stamp = lock.writeLock();
      try {
        if (closed) { return; }
        closed = true;
        closeFuture.complete(null);
        try { out.close(); } catch (IOException ignored) {}
      } finally {
        lock.unlockWrite(stamp);
      }
    }

    void writeSseEvent(McpSchema.JSONRPCMessage message, String messageId) {
      boolean shouldClose = false;
      long stamp = lock.writeLock();
      try {
        if (closed) { return; }
        var json = jsonMapper.writeValueAsString(message);
        var event = formatSseEvent(messageId, json);
        out.write(event);
        out.flush();
      } catch (IOException e) {
        log.debug("Client disconnected: {}", e.getMessage());
        shouldClose = true;
      } catch (Exception e) {
        log.error("SSE write failed", e);
        shouldClose = true;
      } finally {
        lock.unlockWrite(stamp);
      }
      if (shouldClose) { close(); }
    }

    /// Blocks the calling virtual thread until this transport is closed.
    void awaitClose() {
      try { closeFuture.join(); } catch (Exception ignored) {}
    }

    private static byte[] formatSseEvent(String id, String data) {
      var sb = new StringBuilder(data.length() + 40);
      if (id != null) { sb.append("id: ").append(id).append('\n'); }
      sb.append("event: ").append(SSE_MESSAGE_EVENT).append('\n');
      sb.append("data: ").append(data).append("\n\n");
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
  }
}
