package io.mcpbridge.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

final class WeatherToolsTest {

  /// Null client → stub responses for all tools.
  private final WeatherTools tools = new WeatherTools(null);

  @Test
  void all_returnsThreeTools() {
    var specs = tools.all();
    assertThat(specs).hasSize(3);
    assertThat(specs.stream().map(s -> s.tool().name()))
        .containsExactly("getCurrentWeather", "getForecast", "searchLocations");
  }

  @Test
  void getCurrentWeather_validatesCity() {
    var spec = tool("getCurrentWeather");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("getCurrentWeather", Map.of("city", "", "unit", "celsius")));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("City name is required");
  }

  @Test
  void getCurrentWeather_validatesUnit() {
    var spec = tool("getCurrentWeather");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("getCurrentWeather", Map.of("city", "London", "unit", "")));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("Unit is required");
  }

  @Test
  void getCurrentWeather_validatesMaxLength() {
    var spec = tool("getCurrentWeather");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("getCurrentWeather", Map.of("city", "x".repeat(101), "unit", "celsius")));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("exceeds maximum length");
  }

  @Test
  void getCurrentWeather_nullClient_returnsNotConfigured() {
    var spec = tool("getCurrentWeather");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("getCurrentWeather", Map.of("city", "London", "unit", "celsius")));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("WEATHER_API_KEY");
  }

  @Test
  void getForecast_validatesCity() {
    var spec = tool("getForecast");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("getForecast", Map.of("city", "", "days", 3)));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("City name is required");
  }

  @Test
  void searchLocations_validatesQuery() {
    var spec = tool("searchLocations");
    var result = spec.callHandler().apply(null,
        new CallToolRequest("searchLocations", Map.of("query", "")));

    assertThat(result.isError()).isTrue();
    assertThat(text(result)).contains("Search query is required");
  }

  @Test
  void allTools_haveDescriptionsAndSchemas() {
    for (var spec : tools.all()) {
      assertThat(spec.tool().description()).isNotBlank();
      assertThat(spec.tool().inputSchema()).isNotNull();
      assertThat(spec.tool().inputSchema().type()).isEqualTo("object");
      assertThat(spec.tool().inputSchema().required()).isNotEmpty();
    }
  }

  private io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification tool(String name) {
    return tools.all().stream()
        .filter(s -> name.equals(s.tool().name()))
        .findFirst().orElseThrow();
  }

  private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
    return ((TextContent) result.content().getFirst()).text();
  }
}
