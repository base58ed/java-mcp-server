package io.mcpbridge.mcp.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastResponse(
    WeatherResponse.Location location,
    Forecast forecast
) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Forecast(@JsonProperty("forecastday") List<ForecastDay> forecastDay) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ForecastDay(String date, Day day) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Day(
      @JsonProperty("maxtemp_c") double maxTempC,
      @JsonProperty("mintemp_c") double minTempC,
      @JsonProperty("avgtemp_c") double avgTempC,
      @JsonProperty("maxwind_kph") double maxWindKph,
      @JsonProperty("daily_chance_of_rain") int dailyChanceOfRain,
      WeatherResponse.Condition condition) {}
}
