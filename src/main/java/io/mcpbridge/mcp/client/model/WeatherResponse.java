package io.mcpbridge.mcp.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherResponse(Location location, Current current) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Location(
      String name, String region, String country,
      double lat, double lon,
      @JsonProperty("tz_id") String tzId,
      @JsonProperty("localtime") String localTime) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Current(
      @JsonProperty("temp_c") double tempC,
      @JsonProperty("temp_f") double tempF,
      Condition condition,
      @JsonProperty("wind_kph") double windKph,
      int humidity,
      @JsonProperty("feelslike_c") double feelslikeC,
      @JsonProperty("feelslike_f") double feelslikeF) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Condition(String text, String icon, int code) {}
}
