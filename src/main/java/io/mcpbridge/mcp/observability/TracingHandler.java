package io.mcpbridge.mcp.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderValues;
import io.undertow.util.StatusCodes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Undertow handler wrapper that creates OTel spans and correlation IDs for incoming requests.
///
/// Wraps the next handler: starts a span on entry, ends it on completion,
/// and runs the handler within a CorrelationId scope.
public final class TracingHandler implements HttpHandler {

  private static final Logger log = LogManager.getLogger();

  private final HttpHandler next;
  private final Tracer tracer;

  public TracingHandler(HttpHandler next, Tracer tracer) {
    assert next != null : "next handler required";
    assert tracer != null : "tracer required";
    this.next = next;
    this.tracer = tracer;
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    var method = exchange.getRequestMethod().toString();
    var path = exchange.getRequestPath();

    // Use incoming correlation header or generate new
    var incomingId = header(exchange, "x-correlation-id");
    var correlationId = (incomingId != null && !incomingId.isBlank())
        ? incomingId : CorrelationId.generate();

    var span = tracer.spanBuilder(method + " " + path)
        .setSpanKind(SpanKind.SERVER)
        .setAttribute("http.method", method)
        .setAttribute("http.url", exchange.getRequestURL())
        .setAttribute("http.target", path)
        .setAttribute("correlation.id", correlationId)
        .startSpan();

    exchange.addExchangeCompleteListener((ex, nextListener) -> {
      span.setAttribute("http.status_code", ex.getStatusCode());
      if (ex.getStatusCode() >= 500) {
        span.setStatus(StatusCode.ERROR);
      }
      span.end();
      nextListener.proceed();
    });

    // Set correlation ID response header
    exchange.getResponseHeaders().put(
        io.undertow.util.HttpString.tryFromString("x-correlation-id"), correlationId);

    var ctx = Context.current().with(span);
    try (var ignored = ctx.makeCurrent()) {
      CorrelationId.run(correlationId, () -> {
        try {
          next.handleRequest(exchange);
        } catch (Exception e) {
          span.recordException(e);
          span.setStatus(StatusCode.ERROR, e.getMessage());
          log.error("{} {} failed", method, path, e);
          if (!exchange.isResponseStarted()) {
            exchange.setStatusCode(StatusCodes.INTERNAL_SERVER_ERROR);
            exchange.endExchange();
          }
        }
      });
    }
  }

  private static String header(HttpServerExchange exchange, String name) {
    HeaderValues values = exchange.getRequestHeaders().get(name);
    return (values != null && !values.isEmpty()) ? values.getFirst() : null;
  }
}
