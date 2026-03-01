package io.mcpbridge.mcp.health;

import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/// Serves `/health/live`, `/health/ready`, `/health/started` endpoints.
/// Readiness checks are registrable — add external dependency checks at startup.
public final class HealthHandler implements HttpHandler {

  private final GracefulShutdown shutdown;
  private final List<NamedCheck> readinessChecks = new CopyOnWriteArrayList<>();
  private volatile boolean started = false;

  public HealthHandler(GracefulShutdown shutdown) {
    this.shutdown = shutdown;
  }

  public void markStarted() { this.started = true; }

  public void addReadinessCheck(String name, BooleanSupplier check) {
    readinessChecks.add(new NamedCheck(name, check));
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) {
    var path = exchange.getRequestPath();
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");

    switch (path) {
      case "/health/live" -> sendStatus(exchange, !shutdown.isShuttingDown());
      case "/health/ready" -> sendStatus(exchange, isReady());
      case "/health/started" -> sendStatus(exchange, started);
      default -> {
        exchange.setStatusCode(StatusCodes.NOT_FOUND);
        exchange.getResponseSender().send("{\"status\":\"NOT_FOUND\"}");
      }
    }
  }

  boolean isReady() {
    if (shutdown.isShuttingDown()) { return false; }
    for (var check : readinessChecks) {
      if (!check.supplier.getAsBoolean()) { return false; }
    }
    return started;
  }

  private void sendStatus(HttpServerExchange exchange, boolean healthy) {
    if (healthy) {
      exchange.setStatusCode(StatusCodes.OK);
      exchange.getResponseSender().send("{\"status\":\"UP\"}");
    } else {
      exchange.setStatusCode(StatusCodes.SERVICE_UNAVAILABLE);
      exchange.getResponseSender().send("{\"status\":\"DOWN\"}");
    }
  }

  private record NamedCheck(String name, BooleanSupplier supplier) {}
}