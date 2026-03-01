package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.config.Config.CorsConfig;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

/// CORS middleware — wraps an inner handler with configurable origin/method/header rules.
public final class CorsHandler implements HttpHandler {

  private static final HttpString ACCESS_CONTROL_ALLOW_ORIGIN = new HttpString("Access-Control-Allow-Origin");
  private static final HttpString ACCESS_CONTROL_ALLOW_METHODS = new HttpString("Access-Control-Allow-Methods");
  private static final HttpString ACCESS_CONTROL_ALLOW_HEADERS = new HttpString("Access-Control-Allow-Headers");
  private static final HttpString ACCESS_CONTROL_MAX_AGE = new HttpString("Access-Control-Max-Age");

  private final HttpHandler next;
  private final String allowedOrigins;
  private final String allowedMethods;

  public CorsHandler(HttpHandler next, CorsConfig config) {
    this.next = next;
    this.allowedOrigins = String.join(", ", config.origins());
    this.allowedMethods = String.join(", ", config.methods());
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    exchange.getResponseHeaders()
        .put(ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigins)
        .put(ACCESS_CONTROL_ALLOW_METHODS, allowedMethods)
        .put(ACCESS_CONTROL_ALLOW_HEADERS, Headers.CONTENT_TYPE_STRING + ", Accept, mcp-session-id")
        .put(ACCESS_CONTROL_MAX_AGE, "86400");

    if (Methods.OPTIONS.equals(exchange.getRequestMethod())) {
      exchange.setStatusCode(StatusCodes.NO_CONTENT);
      exchange.endExchange();
      return;
    }

    next.handleRequest(exchange);
  }
}