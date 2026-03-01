package io.mcpbridge.mcp.client;

import io.mcpbridge.mcp.client.model.ForecastResponse;
import io.mcpbridge.mcp.client.model.LocationSearchResponse;
import io.mcpbridge.mcp.client.model.WeatherResponse;
import io.mcpbridge.mcp.common.Result;
import io.mcpbridge.mcp.observability.ClientMetrics;
import dev.failsafe.CircuitBreaker;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/// Weather API client using Apache HttpClient 5 + Failsafe resilience.
///
/// Retry and circuit breaker handle `IOException` (network errors, 5xx).
/// Client errors (4xx) and parse errors return `Result.Err` without retry.
public final class WeatherApiClient implements AutoCloseable {

  private static final Logger log = LogManager.getLogger();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<List<LocationSearchResponse>> LIST_LOCATIONS = new TypeReference<>() {};

  private final CloseableHttpClient http;
  private final String baseUrl;
  private final String apiKey;
  private final CircuitBreaker<Object> circuitBreaker;
  private final RetryPolicy<Object> retry;
  private final ClientMetrics metrics;

  public WeatherApiClient(CloseableHttpClient http, String baseUrl, String apiKey,
                           RetryPolicy<Object> retry, CircuitBreaker<Object> circuitBreaker,
                           ClientMetrics metrics) {
    assert http != null : "http required";
    assert baseUrl != null && !baseUrl.isBlank() : "baseUrl required";
    assert apiKey != null && !apiKey.isBlank() : "apiKey required";
    this.http = http;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.retry = retry;
    this.circuitBreaker = circuitBreaker;
    this.metrics = metrics;
  }

  public Result<WeatherResponse> getCurrentWeather(String query) {
    return get("/v1/current.json", Map.of("q", query), WeatherResponse.class);
  }

  public Result<ForecastResponse> getForecast(String query, int days) {
    return get("/v1/forecast.json", Map.of("q", query, "days", String.valueOf(days)), ForecastResponse.class);
  }

  public Result<List<LocationSearchResponse>> searchLocations(String query) {
    return get("/v1/search.json", Map.of("q", query), LIST_LOCATIONS);
  }

  @Override
  public void close() {
    try { http.close(); } catch (IOException e) {
      log.warn("Failed to close HTTP client: {}", e.getMessage());
    }
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  private <T> Result<T> get(String path, Map<String, String> params, Class<T> type) {
    return exec(path, params, resp -> JSON.readValue(resp.getEntity().getContent(), type));
  }

  private <T> Result<T> get(String path, Map<String, String> params, TypeReference<T> type) {
    return exec(path, params, resp -> JSON.readValue(resp.getEntity().getContent(), type));
  }

  /// Executes a GET request with Failsafe retry + circuit breaker.
  /// IOException (network / 5xx) → retried. RuntimeException (4xx / parse) → immediate Err.
  private <T> Result<T> exec(String path, Map<String, String> params, ResponseReader<T> reader) {
    long start = System.nanoTime();
    var result = Result.of(() -> Failsafe.with(circuitBreaker, retry).get(() -> {
      var uri = new URIBuilder(baseUrl + path).addParameter("key", apiKey);
      params.forEach(uri::addParameter);
      return http.execute(new HttpGet(uri.build()), response -> {
        var status = response.getCode();
        if (status >= 500) { throw new IOException("HTTP " + status + " from " + path); }
        if (status >= 400) { throw new ApiException(status, path); }
        return reader.read(response);
      });
    }));
    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
    metrics.recordRequest(path, result.isOk() ? "success" : "error");
    metrics.recordDuration(path, seconds);
    return result;
  }

  @FunctionalInterface
  private interface ResponseReader<T> {
    T read(ClassicHttpResponse response) throws IOException;
  }

  /// Client error (4xx) — not retried by Failsafe since it's not IOException.
  private static final class ApiException extends RuntimeException {
    ApiException(int status, String path) { super("HTTP " + status + " from " + path); }
  }
}
