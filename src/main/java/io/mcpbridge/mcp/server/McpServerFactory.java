package io.mcpbridge.mcp.server;

import io.mcpbridge.mcp.client.HttpClientFactory;
import io.mcpbridge.mcp.client.WeatherApiClient;
import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.observability.ClientMetrics;
import io.mcpbridge.mcp.resilience.Policies;
import io.mcpbridge.mcp.tool.WeatherTools;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.undertow.server.RoutingHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/// Builds the MCP server with transport, tools, and HTTP client wired together.
public final class McpServerFactory {

  private static final Logger log = LogManager.getLogger();

  private McpServerFactory() {}

  public record McpSetup(UndertowMcpTransport transport, McpSyncServer server,
                          Runnable cleanup) implements AutoCloseable {

    public void mountRoutes(RoutingHandler routes) {
      routes
          .post("/mcp", transport)
          .get("/mcp", transport)
          .delete("/mcp", transport);
    }

    @Override
    public void close() {
      server.close();
      cleanup.run();
    }
  }

  public static McpSetup create(Config config, Map<String, String> env, ClientMetrics clientMetrics) {
    var transport = new UndertowMcpTransport(McpJsonDefaults.getMapper());

    // HTTP client + resilience (if API key and base URL configured)
    var apiKey = env.getOrDefault("WEATHER_API_KEY", "");
    var clientConfig = config.client().getOrDefault("weather-api", new Config.ClientConfig(null, 0, 0, 0));
    WeatherApiClient weatherClient = null;
    if (!apiKey.isBlank() && !clientConfig.baseUrl().isBlank()) {
      var http = HttpClientFactory.create(clientConfig);
      var retry = Policies.<Object>retry(config.resilience(), clientMetrics);
      var cb = Policies.<Object>circuitBreaker(config.resilience(), clientMetrics);
      weatherClient = new WeatherApiClient(http, clientConfig.baseUrl(), apiKey, retry, cb, clientMetrics);
      log.info("Weather API client configured: {}", clientConfig.baseUrl());
    } else {
      log.warn("Weather API not configured — weather tools will return stub responses");
    }

    var tools = new WeatherTools(weatherClient);
    var server = McpServer.sync(transport)
        .serverInfo(config.mcp().name(), config.mcp().version())
        .tools(tools.all())
        .build();

    var client = weatherClient;
    return new McpSetup(transport, server, () -> {
      if (client != null) { client.close(); }
    });
  }
}
