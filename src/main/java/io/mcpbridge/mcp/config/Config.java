package io.mcpbridge.mcp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/// Typed config records deserialized from `config.toml`.
/// Jackson maps TOML sections directly to nested records.
public record Config(
    ServerConfig server,
    McpConfig mcp,
    Map<String, ClientConfig> client,
    ResilienceConfig resilience,
    TelemetryConfig telemetry,
    LoggingConfig logging
) {

  public Config {
    if (server == null) { server = ServerConfig.defaults(); }
    if (mcp == null) { mcp = McpConfig.defaults(); }
    if (client == null) { client = Map.of(); }
    if (resilience == null) { resilience = ResilienceConfig.defaults(); }
    if (telemetry == null) { telemetry = TelemetryConfig.defaults(); }
    if (logging == null) { logging = LoggingConfig.defaults(); }
  }

  public record ServerConfig(
      String host,
      int port,
      @JsonProperty("shutdown-timeout-seconds") int shutdownTimeoutSeconds,
      @JsonProperty("drain-delay-ms") int drainDelayMs,
      CorsConfig cors
  ) {
    static ServerConfig defaults() {
      return new ServerConfig("0.0.0.0", 8181, 30, 3000, CorsConfig.defaults());
    }

    public ServerConfig {
      if (host == null) { host = "0.0.0.0"; }
      if (port <= 0) { port = 8181; }
      if (shutdownTimeoutSeconds <= 0) { shutdownTimeoutSeconds = 30; }
      if (drainDelayMs < 0) { drainDelayMs = 3000; }
      if (cors == null) { cors = CorsConfig.defaults(); }
    }
  }

  public record CorsConfig(
      boolean enabled,
      List<String> origins,
      List<String> methods
  ) {
    static CorsConfig defaults() {
      return new CorsConfig(true, List.of("*"), List.of("GET", "POST", "OPTIONS"));
    }

    public CorsConfig {
      if (origins == null) { origins = List.of("*"); }
      if (methods == null) { methods = List.of("GET", "POST", "OPTIONS"); }
    }
  }

  public record McpConfig(
      String name,
      String version,
      String description
  ) {
    static McpConfig defaults() {
      return new McpConfig("java-mcp-server", "1.0.0", "MCP server template");
    }

    public McpConfig {
      if (name == null) { name = "java-mcp-server"; }
      if (version == null) { version = "1.0.0"; }
      if (description == null) { description = "MCP server template"; }
    }
  }

  public record ClientConfig(
      @JsonProperty("base-url") String baseUrl,
      @JsonProperty("connect-timeout-ms") int connectTimeoutMs,
      @JsonProperty("response-timeout-ms") int responseTimeoutMs,
      @JsonProperty("pool-size") int poolSize
  ) {
    public ClientConfig {
      if (baseUrl == null) { baseUrl = ""; }
      if (connectTimeoutMs <= 0) { connectTimeoutMs = 5000; }
      if (responseTimeoutMs <= 0) { responseTimeoutMs = 10000; }
      if (poolSize <= 0) { poolSize = 20; }
    }
  }

  public record ResilienceConfig(
      @JsonProperty("max-retries") int maxRetries,
      @JsonProperty("retry-delay-ms") int retryDelayMs,
      @JsonProperty("retry-jitter-ms") int retryJitterMs,
      @JsonProperty("circuit-breaker-failure-threshold") int circuitBreakerFailureThreshold,
      @JsonProperty("circuit-breaker-failure-count") int circuitBreakerFailureCount,
      @JsonProperty("circuit-breaker-delay-ms") int circuitBreakerDelayMs
  ) {
    static ResilienceConfig defaults() {
      return new ResilienceConfig(3, 500, 200, 5, 10, 30_000);
    }

    public ResilienceConfig {
      if (maxRetries <= 0) { maxRetries = 3; }
      if (retryDelayMs <= 0) { retryDelayMs = 500; }
      if (retryJitterMs < 0) { retryJitterMs = 200; }
      if (circuitBreakerFailureThreshold <= 0) { circuitBreakerFailureThreshold = 5; }
      if (circuitBreakerFailureCount <= 0) { circuitBreakerFailureCount = 10; }
      if (circuitBreakerDelayMs <= 0) { circuitBreakerDelayMs = 30_000; }
    }
  }

  public record TelemetryConfig(
      boolean enabled,
      TracesConfig traces,
      MetricsConfig metrics
  ) {
    public static TelemetryConfig defaults() {
      return new TelemetryConfig(false, TracesConfig.defaults(), MetricsConfig.defaults());
    }

    public TelemetryConfig {
      if (traces == null) { traces = TracesConfig.defaults(); }
      if (metrics == null) { metrics = MetricsConfig.defaults(); }
    }
  }

  public record TracesConfig(
      boolean enabled,
      String endpoint,
      @JsonProperty("sampler-ratio") double samplerRatio
  ) {
    static TracesConfig defaults() {
      return new TracesConfig(false, "http://localhost:4318/v1/traces", 1.0);
    }

    public TracesConfig {
      if (endpoint == null) { endpoint = "http://localhost:4318/v1/traces"; }
      if (samplerRatio <= 0.0 || samplerRatio > 1.0) { samplerRatio = 1.0; }
    }
  }

  public record MetricsConfig(
      boolean enabled,
      String path
  ) {
    static MetricsConfig defaults() {
      return new MetricsConfig(false, "/metrics");
    }

    public MetricsConfig {
      if (path == null) { path = "/metrics"; }
    }
  }

  public record LoggingConfig(
      String level,
      Map<String, String> categories
  ) {
    static LoggingConfig defaults() {
      return new LoggingConfig("INFO", Map.of());
    }

    public LoggingConfig {
      if (level == null) { level = "INFO"; }
      if (categories == null) { categories = Map.of(); }
    }
  }
}