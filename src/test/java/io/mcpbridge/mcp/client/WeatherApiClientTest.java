package io.mcpbridge.mcp.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.observability.ClientMetrics;
import io.mcpbridge.mcp.resilience.Policies;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

final class WeatherApiClientTest {

  private static final ClientMetrics NOOP_METRICS = new ClientMetrics(OpenTelemetry.noop().getMeter("test"));

  private WireMockServer wm;
  private WeatherApiClient client;

  @BeforeEach
  void setUp() {
    wm = new WireMockServer(0);
    wm.start();

    var clientConfig = new Config.ClientConfig(wm.baseUrl(), 2000, 5000, 5);
    var http = HttpClientFactory.create(clientConfig);
    var resilience = new Config.ResilienceConfig(2, 100, 50, 3, 5, 5000);
    var retry = Policies.<Object>retry(resilience, NOOP_METRICS);
    var cb = Policies.<Object>circuitBreaker(resilience, NOOP_METRICS);
    client = new WeatherApiClient(http, wm.baseUrl(), "test-key", retry, cb, NOOP_METRICS);
  }

  @AfterEach
  void tearDown() {
    client.close();
    wm.stop();
  }

  @Nested
  class GetCurrentWeather {

    @Test
    void success_deserializesResponse() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json"))
          .withQueryParam("key", equalTo("test-key"))
          .withQueryParam("q", equalTo("London"))
          .willReturn(okJson(CURRENT_WEATHER_JSON)));

      var result = client.getCurrentWeather("London");

      assertThat(result.isOk()).isTrue();
      var weather = result.unwrap();
      assertThat(weather.location().name()).isEqualTo("London");
      assertThat(weather.current().tempC()).isEqualTo(15.0);
      assertThat(weather.current().humidity()).isEqualTo(72);
      assertThat(weather.current().condition().text()).isEqualTo("Partly cloudy");
    }

    @Test
    void clientError_returnsErr() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json"))
          .willReturn(aResponse().withStatus(400).withBody("bad request")));

      var result = client.getCurrentWeather("???");

      assertThat(result.isErr()).isTrue();
    }

    @Test
    void serverError_retriesThenFails() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json"))
          .willReturn(aResponse().withStatus(503)));

      var result = client.getCurrentWeather("London");

      assertThat(result.isErr()).isTrue();
      // Verify retries happened (more than 1 attempt)
      var count = wm.countRequestsMatching(getRequestedFor(urlPathEqualTo("/v1/current.json")).build()).getCount();
      assertThat(count).isGreaterThan(1);
    }

    @Test
    void serverError_thenSuccess_recovers() {
      wm.stubFor(get(urlPathEqualTo("/v1/current.json"))
          .inScenario("retry")
          .whenScenarioStateIs("Started")
          .willReturn(aResponse().withStatus(503))
          .willSetStateTo("retried"));

      wm.stubFor(get(urlPathEqualTo("/v1/current.json"))
          .inScenario("retry")
          .whenScenarioStateIs("retried")
          .willReturn(okJson(CURRENT_WEATHER_JSON)));

      var result = client.getCurrentWeather("London");

      assertThat(result.isOk()).isTrue();
      assertThat(result.unwrap().location().name()).isEqualTo("London");
    }
  }

  @Nested
  class GetForecast {

    @Test
    void success_deserializesForecast() {
      wm.stubFor(get(urlPathEqualTo("/v1/forecast.json"))
          .withQueryParam("q", equalTo("Tokyo"))
          .withQueryParam("days", equalTo("3"))
          .willReturn(okJson(FORECAST_JSON)));

      var result = client.getForecast("Tokyo", 3);

      assertThat(result.isOk()).isTrue();
      var forecast = result.unwrap();
      assertThat(forecast.location().name()).isEqualTo("Tokyo");
      assertThat(forecast.forecast().forecastDay()).hasSize(1);
      assertThat(forecast.forecast().forecastDay().getFirst().day().maxTempC()).isEqualTo(25.0);
    }
  }

  @Nested
  class SearchLocations {

    @Test
    void success_deserializesList() {
      wm.stubFor(get(urlPathEqualTo("/v1/search.json"))
          .withQueryParam("q", equalTo("Lon"))
          .willReturn(okJson(SEARCH_JSON)));

      var result = client.searchLocations("Lon");

      assertThat(result.isOk()).isTrue();
      var locations = result.unwrap();
      assertThat(locations).hasSize(2);
      assertThat(locations.getFirst().name()).isEqualTo("London");
    }
  }

  // ── Test JSON fixtures ──────────────────────────────────────────────────────

  private static final String CURRENT_WEATHER_JSON = """
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
