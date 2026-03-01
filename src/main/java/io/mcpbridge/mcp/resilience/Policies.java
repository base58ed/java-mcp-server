package io.mcpbridge.mcp.resilience;

import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.observability.ClientMetrics;
import dev.failsafe.CircuitBreaker;
import dev.failsafe.RetryPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Duration;

import static org.apache.logging.log4j.util.Unbox.box;

/// Failsafe policy factories configured from TOML.
///
/// Retry and circuit breaker handle `IOException` only — network errors and 5xx responses.
/// Client errors (4xx) and parse errors pass through without retry.
public final class Policies {

  private static final Logger log = LogManager.getLogger();

  private Policies() {}

  public static <T> RetryPolicy<T> retry(Config.ResilienceConfig config, ClientMetrics metrics) {
    return RetryPolicy.<T>builder()
        .handle(IOException.class)
        .withDelay(Duration.ofMillis(config.retryDelayMs()))
        .withJitter(Duration.ofMillis(config.retryJitterMs()))
        .withMaxRetries(config.maxRetries())
        .onRetry(e -> {
          log.warn("retry {}/{}: {}",
              box(e.getAttemptCount()), box(config.maxRetries()), e.getLastException().getMessage());
          metrics.recordRetry();
        })
        .build();
  }

  public static <T> CircuitBreaker<T> circuitBreaker(Config.ResilienceConfig config, ClientMetrics metrics) {
    return CircuitBreaker.<T>builder()
        .handle(IOException.class)
        .withFailureThreshold(config.circuitBreakerFailureThreshold(), config.circuitBreakerFailureCount())
        .withDelay(Duration.ofMillis(config.circuitBreakerDelayMs()))
        .onOpen(e -> {
          log.warn("circuit breaker opened");
          metrics.recordCircuitBreakerTransition("open");
        })
        .onHalfOpen(e -> {
          log.info("circuit breaker half-open");
          metrics.recordCircuitBreakerTransition("half_open");
        })
        .onClose(e -> {
          log.info("circuit breaker closed");
          metrics.recordCircuitBreakerTransition("closed");
        })
        .build();
  }
}
