package io.mcpbridge.mcp.tool;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mcpbridge.mcp.client.HttpClientFactory;
import io.mcpbridge.mcp.client.WeatherApiClient;
import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.observability.ClientMetrics;
import io.mcpbridge.mcp.resilience.Policies;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/// Tests WeatherTools success paths with a real client backed by WireMock.
final class WeatherToolsIntegrationTest {

  private static final ClientMetrics NOOP_METRICS = new ClientMetrics(OpenTelemetry.noop().getMeter("test"));

  private WireMockServer wm;
  private WeatherTools tools;
  private WeatherApiClient client;

  @BeforeEach
  void setUp() {
    wm = new WireMockServer(0);
    wm.start();

    var clientConfig = new Config.ClientConfig(wm.baseUrl(), 2000, 5000, 5);
    var http = HttpClientFactory.create(clientConfig);
    var resilience = new Config.ResilienceConfig(2, 100, 50, 3, 5, 5000);
    client = new WeatherApiClient(http, wm.baseUrl(), "test-key",
        Policies.retry(resilience, NOOP_METRICS), Policies.circuitBreaker(resilience, NOOP_METRICS), NOOP_METRICS);
    tools = new WeatherTools(client);
  }

  @AfterEach
  void tearDown() {
    client.close();
    wm.stop();
  }

  @Nested class GetCurrentWeather {

    @Test void success_celsius() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json")).willReturn(okJson(WEATHER_JSON)));

      var result = call("getCurrentWeather", Map.of("city", "London", "unit", "celsius"));

      assertThat(result.isError()).isFalse();
      var json = text(result);
      assertThat(json).contains("\"city\":\"London\"");
      assertThat(json).contains("\"unit\":\"celsius\"");
      assertThat(json).contains("\"temperature\":15.0");
    }

    @Test void success_fahrenheit() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json")).willReturn(okJson(WEATHER_JSON)));

      var result = call("getCurrentWeather", Map.of("city", "London", "unit", "fahrenheit"));

      assertThat(result.isError()).isFalse();
      var json = text(result);
      assertThat(json).contains("\"unit\":\"fahrenheit\"");
      assertThat(json).contains("\"temperature\":59.0");
    }

    @Test void success_fahrenheitShorthand() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json")).willReturn(okJson(WEATHER_JSON)));

      var result = call("getCurrentWeather", Map.of("city", "London", "unit", "f"));

      var json = text(result);
      assertThat(json).contains("\"unit\":\"fahrenheit\"");
    }

    @Test void apiError_returnsError() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json")).willReturn(aResponse().withStatus(400)));

      var result = call("getCurrentWeather", Map.of("city", "???", "unit", "celsius"));

      assertThat(result.isError()).isTrue();
    }
  }

  @Nested class GetForecast {

    @Test void success_returnsDayForecasts() {
      wm.stubFor(get(urlPathEqualTo("/v1/forecast.json")).willReturn(okJson(FORECAST_JSON)));

      var result = call("getForecast", Map.of("city", "Tokyo", "days", 3));

      assertThat(result.isError()).isFalse();
      var json = text(result);
      assertThat(json).contains("\"city\":\"Tokyo\"");
      assertThat(json).contains("\"highC\":25.0");
      assertThat(json).contains("\"condition\":\"Sunny\"");
    }

    @Test void success_clampsDaysToRange() {
      wm.stubFor(get(urlPathEqualTo("/v1/forecast.json"))
          .withQueryParam("days", equalTo("7"))
          .willReturn(okJson(FORECAST_JSON)));

      // days=99 should be clamped to 7
      var result = call("getForecast", Map.of("city", "Tokyo", "days", 99));

      assertThat(result.isError()).isFalse();
    }

    @Test void success_defaultsDaysWhenNonNumeric() {
      wm.stubFor(get(urlPathEqualTo("/v1/forecast.json"))
          .withQueryParam("days", equalTo("3"))
          .willReturn(okJson(FORECAST_JSON)));

      // non-Number defaults to 3
      var result = call("getForecast", Map.of("city", "Tokyo", "days", "abc"));

      assertThat(result.isError()).isFalse();
    }

    @Test void apiError_returnsError() {
      wm.stubFor(get(urlPathEqualTo("/v1/forecast.json")).willReturn(aResponse().withStatus(400)));

      var result = call("getForecast", Map.of("city", "???", "days", 3));

      assertThat(result.isError()).isTrue();
    }

    @Test void validatesMaxLength() {
      var result = call("getForecast", Map.of("city", "x".repeat(101), "days", 3));
      assertThat(result.isError()).isTrue();
      assertThat(text(result)).contains("exceeds maximum length");
    }

    @Test void nullClient_returnsNotConfigured() {
      var nullTools = new WeatherTools(null);
      var spec = nullTools.all().stream()
          .filter(s -> "getForecast".equals(s.tool().name())).findFirst().orElseThrow();
      var result = spec.callHandler().apply(null,
          new CallToolRequest("getForecast", Map.of("city", "London", "days", 3)));
      assertThat(result.isError()).isTrue();
      assertThat(text(result)).contains("WEATHER_API_KEY");
    }
  }

  @Nested class SearchLocations {

    @Test void success_returnsMatches() {
      wm.stubFor(get(urlPathEqualTo("/v1/search.json")).willReturn(okJson(SEARCH_JSON)));

      var result = call("searchLocations", Map.of("query", "Lon"));

      assertThat(result.isError()).isFalse();
      var json = text(result);
      assertThat(json).contains("\"query\":\"Lon\"");
      assertThat(json).contains("\"name\":\"London\"");
      assertThat(json).contains("\"name\":\"Londonderry\"");
    }

    @Test void apiError_returnsError() {
      wm.stubFor(get(urlPathEqualTo("/v1/search.json")).willReturn(aResponse().withStatus(400)));

      var result = call("searchLocations", Map.of("query", "???"));

      assertThat(result.isError()).isTrue();
    }

    @Test void validatesMaxLength() {
      var result = call("searchLocations", Map.of("query", "x".repeat(101)));
      assertThat(result.isError()).isTrue();
      assertThat(text(result)).contains("exceeds maximum length");
    }

    @Test void nullClient_returnsNotConfigured() {
      var nullTools = new WeatherTools(null);
      var spec = nullTools.all().stream()
          .filter(s -> "searchLocations".equals(s.tool().name())).findFirst().orElseThrow();
      var result = spec.callHandler().apply(null,
          new CallToolRequest("searchLocations", Map.of("query", "Lon")));
      assertThat(result.isError()).isTrue();
      assertThat(text(result)).contains("WEATHER_API_KEY");
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private CallToolResult call(String toolName, Map<String, Object> args) {
    var spec = tools.all().stream()
        .filter(s -> toolName.equals(s.tool().name()))
        .findFirst().orElseThrow();
    return spec.callHandler().apply(null, new CallToolRequest(toolName, args));
  }

  private static String text(CallToolResult result) {
    return ((TextContent) result.content().getFirst()).text();
  }

  // ── Fixtures ─────────────────────────────────────────────────────────────

  private static final String WEATHER_JSON = """
      {
        "location": {"name": "London", "region": "City of London", "country": "UK", "lat": 51.52, "lon": -0.11},
        "current": {"temp_c": 15.0, "temp_f": 59.0, "humidity": 72, "wind_kph": 12.5,
                     "feelslike_c": 13.0, "feelslike_f": 55.4,
                     "condition": {"text": "Partly cloudy", "icon": "", "code": 1003}}
      }
      """;

  private static final String FORECAST_JSON = """
      {
        "location": {"name": "Tokyo", "region": "Tokyo", "country": "Japan", "lat": 35.69, "lon": 139.69},
        "forecast": {"forecastday": [
          {"date": "2025-01-15", "day": {"maxtemp_c": 25.0, "mintemp_c": 18.0, "avgtemp_c": 21.5,
           "maxwind_kph": 15.0, "daily_chance_of_rain": 20,
           "condition": {"text": "Sunny", "icon": "", "code": 1000}}}
        ]}
      }
      """;

  private static final String SEARCH_JSON = """
      [
        {"id": 1, "name": "London", "region": "City of London", "country": "UK", "lat": 51.52, "lon": -0.11},
        {"id": 2, "name": "Londonderry", "region": "Derry City", "country": "UK", "lat": 55.0, "lon": -7.32}
      ]
      """;
}