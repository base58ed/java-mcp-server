package io.mcpbridge.mcp.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class ConfigTest {

  @TempDir Path tmp;

  @Nested
  class TomlLoading {

    @Test
    void load_validToml_parsesNestedKeys() throws IOException {
      var toml = tmp.resolve("config.toml");
      Files.writeString(toml, """
          [server]
          host = "127.0.0.1"
          port = 9090

          [server.cors]
          enabled = false
          """);

      var config = ConfigLoader.load(toml, tmp.resolve("nonexistent.env"));

      assertThat(config.get("server.host")).isEqualTo("127.0.0.1");
      assertThat(config.getInt("server.port", 0)).isEqualTo(9090);
      assertThat(config.getBool("server.cors.enabled", true)).isFalse();
    }

    @Test
    void load_missingToml_returnsEmptyConfig() {
      var config = ConfigLoader.load(tmp.resolve("missing.toml"), tmp.resolve("missing.env"));

      assertThat(config.get("anything")).isNull();
      assertThat(config.getOrDefault("key", "fallback")).isEqualTo("fallback");
      assertThat(config.getInt("key", 42)).isEqualTo(42);
      assertThat(config.getBool("key", true)).isTrue();
    }

    @Test
    void load_deeplyNestedToml_flattensToDotSeparated() throws IOException {
      var toml = tmp.resolve("config.toml");
      Files.writeString(toml, """
          [telemetry.traces]
          enabled = true
          endpoint = "http://otel:4318"
          """);

      var config = ConfigLoader.load(toml, tmp.resolve("no.env"));

      assertThat(config.getBool("telemetry.traces.enabled", false)).isTrue();
      assertThat(config.get("telemetry.traces.endpoint")).isEqualTo("http://otel:4318");
    }
  }

  @Nested
  class EnvFileLoading {

    @Test
    void load_envFile_parsesKeyValuePairs() throws IOException {
      // Use keys that won't collide with real environment variables
      var envFile = tmp.resolve(".env");
      Files.writeString(envFile, """
          _TEST_API_KEY=abc123
          _TEST_PORT=7070
          # this is a comment

          _TEST_EMPTY_LINE_ABOVE=yes
          """);

      var config = ConfigLoader.load(tmp.resolve("no.toml"), envFile);

      assertThat(config.get("_TEST_API_KEY")).isEqualTo("abc123");
      assertThat(config.get("_TEST_PORT")).isEqualTo("7070");
      assertThat(config.get("_TEST_EMPTY_LINE_ABOVE")).isEqualTo("yes");
    }

    @Test
    void load_envOverridesToml() throws IOException {
      var toml = tmp.resolve("config.toml");
      Files.writeString(toml, """
          [server]
          port = 8181
          """);

      var envFile = tmp.resolve(".env");
      Files.writeString(envFile, "server.port=9999\n");

      var config = ConfigLoader.load(toml, envFile);

      assertThat(config.get("server.port")).isEqualTo("9999");
    }
  }

  @Nested
  class CompactConstructorDefaults {

    @Test
    void resilienceConfig_zeroValues_usesDefaults() {
      var r = new Config.ResilienceConfig(0, 0, -1, 0, 0, 0);
      assertThat(r.maxRetries()).isEqualTo(3);
      assertThat(r.retryDelayMs()).isEqualTo(500);
      assertThat(r.retryJitterMs()).isEqualTo(200);
      assertThat(r.circuitBreakerFailureThreshold()).isEqualTo(5);
      assertThat(r.circuitBreakerFailureCount()).isEqualTo(10);
      assertThat(r.circuitBreakerDelayMs()).isEqualTo(30_000);
    }

    @Test
    void clientConfig_zeroValues_usesDefaults() {
      var c = new Config.ClientConfig(null, 0, 0, 0);
      assertThat(c.baseUrl()).isEmpty();
      assertThat(c.connectTimeoutMs()).isEqualTo(5000);
      assertThat(c.responseTimeoutMs()).isEqualTo(10000);
      assertThat(c.poolSize()).isEqualTo(20);
    }

    @Test
    void serverConfig_zeroValues_usesDefaults() {
      var s = new Config.ServerConfig(null, 0, 0, 0, null);
      assertThat(s.host()).isEqualTo("0.0.0.0");
      assertThat(s.port()).isEqualTo(8181);
      assertThat(s.shutdownTimeoutSeconds()).isEqualTo(30);
      assertThat(s.drainDelayMs()).isZero(); // 0 is valid (no drain delay)
      assertThat(s.cors()).isNotNull();
    }

    @Test
    void serverConfig_negativeDrainDelay_usesDefault() {
      var s = new Config.ServerConfig(null, 0, 0, -1, null);
      assertThat(s.drainDelayMs()).isEqualTo(3000);
    }

    @Test
    void corsConfig_nullLists_usesDefaults() {
      var c = new Config.CorsConfig(false, null, null);
      assertThat(c.origins()).containsExactly("*");
      assertThat(c.methods()).containsExactly("GET", "POST", "OPTIONS");
    }

    @Test
    void mcpConfig_allNull_usesDefaults() {
      var m = new Config.McpConfig(null, null, null);
      assertThat(m.name()).isEqualTo("java-mcp-server");
      assertThat(m.version()).isEqualTo("1.0.0");
      assertThat(m.description()).isEqualTo("MCP server template");
    }

    @Test
    void tracesConfig_nullEndpoint_usesDefault() {
      var t = new Config.TracesConfig(false, null, 0.0);
      assertThat(t.endpoint()).isEqualTo("http://localhost:4318/v1/traces");
      assertThat(t.samplerRatio()).isEqualTo(1.0);
    }

    @Test
    void tracesConfig_invalidSamplerRatio_clampedToDefault() {
      var t = new Config.TracesConfig(false, "http://x", 2.0);
      assertThat(t.samplerRatio()).isEqualTo(1.0);
    }

    @Test
    void metricsConfig_nullPath_usesDefault() {
      var m = new Config.MetricsConfig(false, null);
      assertThat(m.path()).isEqualTo("/metrics");
    }

    @Test
    void loggingConfig_allNull_usesDefaults() {
      var l = new Config.LoggingConfig(null, null);
      assertThat(l.level()).isEqualTo("INFO");
      assertThat(l.categories()).isEmpty();
    }
  }

  @Nested
  class TypedDeserialization {

    @Test
    void loadAs_fullConfig_deserializesAllSections() throws IOException {
      var toml = tmp.resolve("config.toml");
      Files.writeString(toml, """
          [server]
          host = "0.0.0.0"
          port = 8181
          shutdown-timeout-seconds = 30

          [server.cors]
          enabled = true
          origins = ["*"]
          methods = ["GET", "POST"]

          [mcp]
          name = "test-server"
          version = "2.0.0"
          description = "test"

          [resilience]
          max-retries = 5
          retry-delay-ms = 1000
          retry-jitter-ms = 100
          circuit-breaker-failure-threshold = 3
          circuit-breaker-failure-count = 8
          circuit-breaker-delay-ms = 60000

          [telemetry]
          enabled = true

          [telemetry.traces]
          enabled = true
          endpoint = "http://tempo:4318/v1/traces"
          sampler-ratio = 0.5

          [telemetry.metrics]
          enabled = true
          path = "/prom"

          [logging]
          level = "DEBUG"

          [logging.categories]
          "com.example" = "TRACE"
          """);

      var result = ConfigLoader.loadAs(toml, Config.class);

      assertThat(result.isOk()).isTrue();
      var cfg = result.unwrap();

      assertThat(cfg.server().host()).isEqualTo("0.0.0.0");
      assertThat(cfg.server().port()).isEqualTo(8181);
      assertThat(cfg.server().shutdownTimeoutSeconds()).isEqualTo(30);
      assertThat(cfg.server().cors().enabled()).isTrue();
      assertThat(cfg.server().cors().origins()).containsExactly("*");
      assertThat(cfg.server().cors().methods()).containsExactly("GET", "POST");

      assertThat(cfg.mcp().name()).isEqualTo("test-server");
      assertThat(cfg.mcp().version()).isEqualTo("2.0.0");

      assertThat(cfg.resilience().maxRetries()).isEqualTo(5);
      assertThat(cfg.resilience().retryDelayMs()).isEqualTo(1000);
      assertThat(cfg.resilience().circuitBreakerFailureThreshold()).isEqualTo(3);

      assertThat(cfg.telemetry().enabled()).isTrue();
      assertThat(cfg.telemetry().traces().enabled()).isTrue();
      assertThat(cfg.telemetry().traces().endpoint()).isEqualTo("http://tempo:4318/v1/traces");
      assertThat(cfg.telemetry().traces().samplerRatio()).isEqualTo(0.5);
      assertThat(cfg.telemetry().metrics().enabled()).isTrue();
      assertThat(cfg.telemetry().metrics().path()).isEqualTo("/prom");

      assertThat(cfg.logging().level()).isEqualTo("DEBUG");
      assertThat(cfg.logging().categories()).containsEntry("com.example", "TRACE");
    }

    @Test
    void loadAs_emptyToml_usesDefaults() throws IOException {
      var toml = tmp.resolve("empty.toml");
      Files.writeString(toml, "");

      var result = ConfigLoader.loadAs(toml, Config.class);

      assertThat(result.isOk()).isTrue();
      var cfg = result.unwrap();

      assertThat(cfg.server().host()).isEqualTo("0.0.0.0");
      assertThat(cfg.server().port()).isEqualTo(8181);
      assertThat(cfg.mcp().name()).isEqualTo("java-mcp-server");
      assertThat(cfg.resilience().maxRetries()).isEqualTo(3);
      assertThat(cfg.telemetry().enabled()).isFalse();
      assertThat(cfg.logging().level()).isEqualTo("INFO");
    }

    @Test
    void loadAs_partialConfig_fillsMissingWithDefaults() throws IOException {
      var toml = tmp.resolve("partial.toml");
      Files.writeString(toml, """
          [server]
          port = 9999
          """);

      var result = ConfigLoader.loadAs(toml, Config.class);

      assertThat(result.isOk()).isTrue();
      var cfg = result.unwrap();

      assertThat(cfg.server().port()).isEqualTo(9999);
      assertThat(cfg.server().host()).isEqualTo("0.0.0.0");
      assertThat(cfg.server().shutdownTimeoutSeconds()).isEqualTo(30);
      assertThat(cfg.mcp().name()).isEqualTo("java-mcp-server");
    }

    @Test
    void loadAs_missingFile_returnsErr() {
      var result = ConfigLoader.loadAs(tmp.resolve("nope.toml"), Config.class);
      assertThat(result.isErr()).isTrue();
    }

    @Test
    void loadAs_clientSection_deserializesMap() throws IOException {
      var toml = tmp.resolve("clients.toml");
      Files.writeString(toml, """
          [client.weather-api]
          base-url = "https://api.weather.com"
          connect-timeout-ms = 3000
          response-timeout-ms = 8000
          pool-size = 10

          [client.geocoding-api]
          base-url = "https://api.geocoding.com"
          connect-timeout-ms = 2000
          response-timeout-ms = 5000
          pool-size = 5
          """);

      var cfg = ConfigLoader.loadAs(toml, Config.class).unwrap();

      assertThat(cfg.client()).hasSize(2);
      assertThat(cfg.client().get("weather-api").baseUrl()).isEqualTo("https://api.weather.com");
      assertThat(cfg.client().get("weather-api").connectTimeoutMs()).isEqualTo(3000);
      assertThat(cfg.client().get("geocoding-api").poolSize()).isEqualTo(5);
    }
  }
}