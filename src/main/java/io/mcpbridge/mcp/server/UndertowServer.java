package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.config.Config.ServerConfig;
import io.mcpbridge.mcp.health.HealthHandler;
import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.mcpbridge.mcp.observability.Telemetry;
import io.mcpbridge.mcp.observability.TracingHandler;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.handlers.GracefulShutdownHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Builds and manages the Undertow HTTP server.
/// Virtual thread dispatch for all handlers. Tracing + CORS wrapping when enabled.
public final class UndertowServer {

  private static final Logger log = LogManager.getLogger();

  private final Undertow server;
  private final GracefulShutdownHandler gracefulHandler;
  private final ExecutorService executor;
  private final RoutingHandler routes;
  private final HealthHandler healthHandler;

  private UndertowServer(Undertow server, GracefulShutdownHandler gracefulHandler,
                         ExecutorService executor, RoutingHandler routes,
                         HealthHandler healthHandler) {
    this.server = server;
    this.gracefulHandler = gracefulHandler;
    this.executor = executor;
    this.routes = routes;
    this.healthHandler = healthHandler;
  }

  public static UndertowServer create(
    ServerConfig config,
    GracefulShutdown shutdown,
    Telemetry telemetry
  ) {
    var healthHandler = new HealthHandler(shutdown);
    var routes = new RoutingHandler()
      .get("/health/live", healthHandler)
      .get("/health/ready", healthHandler)
      .get("/health/started", healthHandler)
      .setFallbackHandler(exchange -> {
        exchange.setStatusCode(404);
        exchange.getResponseSender().send("not found");
      });

    HttpHandler root = routes;
    if (config.cors().enabled()) {
      root = new CorsHandler(root, config.cors());
    }
    root = new TracingHandler(root, telemetry.tracer());

    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var gracefulHandler = new GracefulShutdownHandler(virtualThreadDispatch(root, executor));

    var undertow = Undertow.builder()
      .addHttpListener(config.port(), config.host())
      .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, 30_000)
      .setServerOption(UndertowOptions.NO_REQUEST_TIMEOUT, 60_000)
      .setHandler(gracefulHandler)
      .build();

    return new UndertowServer(undertow, gracefulHandler, executor, routes, healthHandler);
  }

  /// Routing handler — use to add MCP or metrics routes before starting.
  public RoutingHandler routes() { return routes; }

  public HealthHandler healthHandler() { return healthHandler; }

  public void start() {
    server.start();
    healthHandler.markStarted();
    var addr = address();
    var display = addr.contains(":") ? "0.0.0.0" : addr;
    log.info("Undertow listening on http://{}:{}", display, port());
  }

  /// Stops the server: rejects new requests, waits for in-flight to drain, then shuts down.
  public void stop() {
    stop(0);
  }

  /// Stops with a drain timeout — waits up to `drainMs` for in-flight requests to complete.
  public void stop(long drainMs) {
    gracefulHandler.shutdown();
    if (drainMs > 0) {
      try {
        if (!gracefulHandler.awaitShutdown(drainMs)) {
          log.warn("drain timeout exceeded after {}ms — forcing stop", drainMs);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("drain interrupted — forcing stop");
      }
    }
    server.stop();
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        log.warn("Forced executor shutdown — some tasks did not complete");
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public int port() {
    var info = server.getListenerInfo();
    if (info.isEmpty()) { return -1; }
    var addr = (java.net.InetSocketAddress) info.getFirst().getAddress();
    return addr.getPort();
  }

  private String address() {
    var info = server.getListenerInfo();
    if (info.isEmpty()) { return "unknown"; }
    var addr = (java.net.InetSocketAddress) info.getFirst().getAddress();
    return addr.getHostString();
  }

  /// Dispatches all requests to virtual threads.
  /// Undertow's I/O threads handle accept/read; business logic runs on virtual threads.
  private static HttpHandler virtualThreadDispatch(HttpHandler handler, ExecutorService executor) {
    return exchange -> {
      if (exchange.isInIoThread()) {
        exchange.dispatch(executor, handler);
      } else {
        handler.handleRequest(exchange);
      }
    };
  }
}