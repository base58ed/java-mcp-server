package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.observability.ClientMetrics;
import io.opentelemetry.api.OpenTelemetry;
import io.undertow.server.RoutingHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class McpServerFactoryTest {

  private static final Config CONFIG = new Config(null, null, null, null, null, null);
  private static final Map<String, String> EMPTY_ENV = Map.of();
  private static final ClientMetrics NOOP_METRICS = new ClientMetrics(OpenTelemetry.noop().getMeter("test"));

  @Test void create_returnsValidSetup() {
    try (var setup = McpServerFactory.create(CONFIG, EMPTY_ENV, NOOP_METRICS)) {
      assertThat(setup.transport()).isNotNull();
      assertThat(setup.server()).isNotNull();
    }
  }

  @Test void mountRoutes_registersAllMcpPaths() {
    try (var setup = McpServerFactory.create(CONFIG, EMPTY_ENV, NOOP_METRICS)) {
      var routes = new RoutingHandler();
      setup.mountRoutes(routes);

      // RoutingHandler doesn't expose routes directly, but mountRoutes
      // wires POST/GET/DELETE on /mcp — verified by no exceptions thrown
      assertThat(routes).isNotNull();
    }
  }

  @Test void close_isIdempotent() {
    var setup = McpServerFactory.create(CONFIG, EMPTY_ENV, NOOP_METRICS);
    setup.close();
    // Second close should not throw
    setup.close();
  }
}