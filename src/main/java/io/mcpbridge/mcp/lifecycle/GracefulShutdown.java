package io.mcpbridge.mcp.lifecycle;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/// Coordinates graceful shutdown with LIFO hook execution and a hard timeout.
/// Register hooks during startup; they execute in reverse order on JVM shutdown.
///
/// Shutdown sequence:
/// 1. Signal — `shuttingDown=true` (health probes go DOWN immediately)
/// 2. Drain delay — pause to let load balancer / K8s remove pod from endpoints
/// 3. LIFO hooks — execute registered hooks in reverse order within the deadline
public final class GracefulShutdown {

  private static final Logger log = LogManager.getLogger();

  private final Deque<NamedHook> hooks = new ArrayDeque<>();
  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private final Instant startupTime = Instant.now();
  private final Duration timeout;
  private final Duration drainDelay;

  public GracefulShutdown(Duration timeout) {
    this(timeout, Duration.ZERO);
  }

  public GracefulShutdown(Duration timeout, Duration drainDelay) {
    this.timeout = timeout;
    this.drainDelay = drainDelay;
  }

  public void registerHook(String name, Runnable action) {
    hooks.addLast(new NamedHook(name, action));
  }

  public void installShutdownHook() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        executeShutdown();
      } catch (Exception e) {
        log.error("Shutdown hook failed", e);
      }
    }, "shutdown"));
  }

  public boolean isShuttingDown() { return shuttingDown.get(); }

  public Duration uptime() { return Duration.between(startupTime, Instant.now()); }

  void executeShutdown() {
    if (!shuttingDown.compareAndSet(false, true)) { return; }
    log.info("shutdown signal received (uptime={}, timeout={}, drainDelay={})",
        uptime(), timeout, drainDelay);

    if (!drainDelay.isZero() && !drainDelay.isNegative()) {
      log.info("draining — waiting {}ms for load balancer to remove endpoint",
          drainDelay.toMillis());
      try {
        Thread.sleep(drainDelay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("drain delay interrupted");
      }
    }

    var deadline = Instant.now().plus(timeout);
    var it = hooks.descendingIterator();
    while (it.hasNext()) {
      if (Instant.now().isAfter(deadline)) {
        log.warn("shutdown timeout exceeded — skipping remaining hooks");
        break;
      }
      var hook = it.next();
      try {
        log.info("running shutdown hook: {}", hook.name);
        hook.action.run();
      } catch (Exception e) {
        log.warn("shutdown hook '{}' failed: {}", hook.name, e.getMessage());
      }
    }
    log.info("shutdown complete");
  }

  private record NamedHook(String name, Runnable action) {}
}