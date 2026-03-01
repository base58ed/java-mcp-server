package io.mcpbridge.mcp.tool;

import io.mcpbridge.mcp.client.WeatherApiClient;
import io.mcpbridge.mcp.common.Result;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/// Weather tool definitions for the MCP server template.
///
/// ## Adding a new tool
///
/// 1. Define a private method returning `SyncToolSpecification`
/// 2. Build a `McpSchema.Tool` with name, description, and JSON Schema input
/// 3. Write the handler: `(exchange, request) -> CallToolResult`
/// 4. Add it to `all()`
public final class WeatherTools {

  private static final int MAX_QUERY_LENGTH = 100;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final WeatherApiClient client;

  /// @param client weather API client, or null for stub responses
  public WeatherTools(WeatherApiClient client) {
    this.client = client;
  }

  public List<SyncToolSpecification> all() {
    return List.of(getCurrentWeather(), getForecast(), searchLocations());
  }

  private SyncToolSpecification getCurrentWeather() {
    var schema = new McpSchema.JsonSchema("object",
        Map.of(
            "city", Map.of("type", "string", "description", "City name (e.g., 'London', 'New York', 'Riyadh')"),
            "unit", Map.of("type", "string", "description", "Temperature unit: 'celsius' or 'fahrenheit'")),
        List.of("city", "unit"), null, null, null);

    var tool = McpSchema.Tool.builder()
        .name("getCurrentWeather")
        .description("Get current weather for a city. Returns temperature, humidity, and conditions.")
        .inputSchema(schema)
        .build();

    return new SyncToolSpecification(tool, (exchange, request) -> {
      var args = request.arguments();
      var city = str(args.get("city"));
      var unit = str(args.get("unit"));

      if (city.isBlank()) { return errorResult("City name is required"); }
      if (city.length() > MAX_QUERY_LENGTH) { return errorResult("City name exceeds maximum length (" + MAX_QUERY_LENGTH + " chars)"); }
      if (unit.isBlank()) { return errorResult("Unit is required (celsius or fahrenheit)"); }
      if (client == null) { return errorResult("Weather API not configured (set WEATHER_API_KEY)"); }

      return switch (client.getCurrentWeather(city)) {
        case Result.Ok(var weather) -> {
          boolean useFahrenheit = "fahrenheit".equalsIgnoreCase(unit) || "f".equalsIgnoreCase(unit);
          var current = weather.current();
          var data = new WeatherResult(
              weather.location().name(), weather.location().country(),
              useFahrenheit ? current.tempF() : current.tempC(),
              useFahrenheit ? "fahrenheit" : "celsius",
              current.humidity(), current.condition().text(),
              current.windKph(),
              useFahrenheit ? current.feelslikeF() : current.feelslikeC());
          yield successResult(data);
        }
        case Result.Err(var f) -> errorResult(f.message());
      };
    });
  }

  private SyncToolSpecification getForecast() {
    var schema = new McpSchema.JsonSchema("object",
        Map.of(
            "city", Map.of("type", "string", "description", "City name"),
            "days", Map.of("type", "integer", "description", "Number of days to forecast (1-7)")),
        List.of("city", "days"), null, null, null);

    var tool = McpSchema.Tool.builder()
        .name("getForecast")
        .description("Get weather forecast for next N days (1-7). Returns daily high/low temperatures and conditions.")
        .inputSchema(schema)
        .build();

    return new SyncToolSpecification(tool, (exchange, request) -> {
      var args = request.arguments();
      var city = str(args.get("city"));
      var daysRaw = args.get("days");

      if (city.isBlank()) { return errorResult("City name is required"); }
      if (city.length() > MAX_QUERY_LENGTH) { return errorResult("City name exceeds maximum length (" + MAX_QUERY_LENGTH + " chars)"); }
      if (client == null) { return errorResult("Weather API not configured (set WEATHER_API_KEY)"); }

      int days = Math.clamp(daysRaw instanceof Number n ? n.intValue() : 3, 1, 7);

      return switch (client.getForecast(city, days)) {
        case Result.Ok(var forecast) -> {
          var dayForecasts = forecast.forecast().forecastDay().stream()
              .map(d -> new DayForecast(
                  d.date(), d.day().maxTempC(), d.day().minTempC(), d.day().avgTempC(),
                  d.day().condition().text(), d.day().dailyChanceOfRain(), d.day().maxWindKph()))
              .toList();
          var data = new ForecastResult(
              forecast.location().name(), forecast.location().country(), dayForecasts);
          yield successResult(data);
        }
        case Result.Err(var f) -> errorResult(f.message());
      };
    });
  }

  private SyncToolSpecification searchLocations() {
    var schema = new McpSchema.JsonSchema("object",
        Map.of("query", Map.of("type", "string", "description", "Partial city name or location to search")),
        List.of("query"), null, null, null);

    var tool = McpSchema.Tool.builder()
        .name("searchLocations")
        .description("Search for cities matching a query. Useful for finding exact city names.")
        .inputSchema(schema)
        .build();

    return new SyncToolSpecification(tool, (exchange, request) -> {
      var query = str(request.arguments().get("query"));

      if (query.isBlank()) { return errorResult("Search query is required"); }
      if (query.length() > MAX_QUERY_LENGTH) { return errorResult("Search query exceeds maximum length (" + MAX_QUERY_LENGTH + " chars)"); }
      if (client == null) { return errorResult("Weather API not configured (set WEATHER_API_KEY)"); }

      return switch (client.searchLocations(query)) {
        case Result.Ok(var locations) -> {
          var matches = locations.stream()
              .map(loc -> new LocationMatch(loc.name(), loc.region(), loc.country(), loc.lat(), loc.lon()))
              .toList();
          yield successResult(new LocationSearchResult(query, matches));
        }
        case Result.Err(var f) -> errorResult(f.message());
      };
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private static CallToolResult successResult(Object data) {
    try {
      return CallToolResult.builder()
          .content(List.of(new TextContent(JSON.writeValueAsString(data))))
          .build();
    } catch (Exception e) {
      return errorResult("Failed to serialize result: " + e.getMessage());
    }
  }

  private static CallToolResult errorResult(String message) {
    return CallToolResult.builder()
        .content(List.of(new TextContent(message)))
        .isError(true)
        .build();
  }

  private static String str(Object value) {
    return value != null ? value.toString() : "";
  }

  // ── Result Records ─────────────────────────────────────────────────────────

  record WeatherResult(String city, String country, double temperature, String unit,
                       int humidity, String condition, double windKph, double feelsLike) {}

  record ForecastResult(String city, String country, List<DayForecast> days) {}

  record DayForecast(String date, double highC, double lowC, double avgC,
                     String condition, int chanceOfRain, double maxWindKph) {}

  record LocationSearchResult(String query, List<LocationMatch> matches) {}

  record LocationMatch(String name, String region, String country, double lat, double lon) {}
}
