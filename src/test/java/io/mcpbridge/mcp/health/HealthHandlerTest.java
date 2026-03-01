package io.mcpbridge.mcp.health;

import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

final class HealthHandlerTest {

  @Test
  void concurrentAddAndIterate_noConcurrentModificationException() throws Exception {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var handler = new HealthHandler(gs);
    handler.markStarted();

    // Pre-populate with one check so isReady() has something to iterate
    handler.addReadinessCheck("base", () -> true);

    var stop = new AtomicBoolean(false);
    var readExceptions = new AtomicInteger(0);
    var readCount = new AtomicInteger(0);

    // Reader: continuously calls isReady() which iterates CopyOnWriteArrayList
    var reader = Thread.ofVirtual().start(() -> {
      while (!stop.get()) {
        try {
          handler.isReady();
          readCount.incrementAndGet();
        } catch (Exception e) {
          readExceptions.incrementAndGet();
        }
      }
    });

    // Writer: adds checks concurrently
    int additions = 100;
    for (int i = 0; i < additions; i++) {
      int idx = i;
      handler.addReadinessCheck("check-" + idx, () -> true);
    }

    stop.set(true);
    reader.join(5_000);

    // CopyOnWriteArrayList guarantees no ConcurrentModificationException
    assertThat(readExceptions.get()).isZero();
    assertThat(readCount.get()).isPositive();
  }

  @Test
  void addedCheck_eventuallyVisibleToReader() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var handler = new HealthHandler(gs);
    handler.markStarted();

    // Add a check that returns false — isReady() should see it
    handler.addReadinessCheck("failing", () -> false);

    await().atMost(1, SECONDS).untilAsserted(() ->
        assertThat(handler.isReady()).isFalse());
  }
}
