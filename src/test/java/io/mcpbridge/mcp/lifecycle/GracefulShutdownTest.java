package io.mcpbridge.mcp.lifecycle;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

final class GracefulShutdownTest {

  @Test void hooks_executeInLifoOrder() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var order = new ArrayList<String>();
    gs.registerHook("first", () -> order.add("first"));
    gs.registerHook("second", () -> order.add("second"));
    gs.registerHook("third", () -> order.add("third"));

    gs.executeShutdown();

    assertThat(order).containsExactly("third", "second", "first");
  }

  @Test void executeShutdown_setsShuttingDown() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    assertThat(gs.isShuttingDown()).isFalse();

    gs.executeShutdown();

    assertThat(gs.isShuttingDown()).isTrue();
  }

  @Test void executeShutdown_idempotent() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var count = new int[]{0};
    gs.registerHook("counter", () -> count[0]++);

    gs.executeShutdown();
    gs.executeShutdown(); // second call should be no-op

    assertThat(count[0]).isEqualTo(1);
  }

  @Test void hookException_doesNotStopOtherHooks() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var executed = new ArrayList<String>();
    gs.registerHook("first", () -> executed.add("first"));
    gs.registerHook("failing", () -> { throw new RuntimeException("boom"); });
    gs.registerHook("third", () -> executed.add("third"));

    gs.executeShutdown();

    // LIFO: third runs, failing throws, first still runs
    assertThat(executed).containsExactly("third", "first");
  }

  @Test void uptime_isPositive() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    assertThat(gs.uptime()).isPositive();
  }

  @Test void drainDelay_pausesBeforeHooks() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10), Duration.ofMillis(100));
    var hookRanAt = new long[]{0};
    gs.registerHook("timer", () -> hookRanAt[0] = System.nanoTime());

    long before = System.nanoTime();
    gs.executeShutdown();

    // Hook should have run at least 80ms after start (allowing some scheduling slack)
    long elapsedMs = (hookRanAt[0] - before) / 1_000_000;
    assertThat(elapsedMs).isGreaterThanOrEqualTo(80);
  }

  @Test void zeroDrainDelay_skipsWait() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10), Duration.ZERO);
    var executed = new ArrayList<String>();
    gs.registerHook("hook", () -> executed.add("ran"));

    gs.executeShutdown();

    assertThat(executed).containsExactly("ran");
  }

  @Test void drainDelay_interruptedThread_continuesShutdown() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10), Duration.ofMillis(500));
    var executed = new ArrayList<String>();
    gs.registerHook("hook", () -> executed.add("ran"));

    // Interrupt the current thread before shutdown — the sleep will catch it
    Thread.currentThread().interrupt();
    gs.executeShutdown();

    assertThat(executed).containsExactly("ran");
    // Clear interrupted status if set
    Thread.interrupted();
  }

  @Test void installShutdownHook_doesNotThrow() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    // Just verify it doesn't throw; the hook won't actually fire in tests
    gs.installShutdownHook();
    assertThat(gs.isShuttingDown()).isFalse();
  }

  @Test void timeoutExceeded_skipsRemainingHooks() {
    var gs = new GracefulShutdown(Duration.ofMillis(50));
    var executed = new ArrayList<String>();
    gs.registerHook("first", () -> executed.add("first"));
    gs.registerHook("slow", () -> {
      try { Thread.sleep(100); } catch (InterruptedException ignored) {}
      executed.add("slow");
    });

    gs.executeShutdown();

    // LIFO: "slow" runs first (takes 100ms, exceeds 50ms timeout)
    // When loop checks "first", deadline has passed → skips it
    assertThat(executed).containsExactly("slow");
  }

  @Test void shuttingDown_trueImmediatelyDuringDrain() {
    var gs = new GracefulShutdown(Duration.ofSeconds(10), Duration.ofMillis(200));
    var statesDuringDrain = new ArrayList<Boolean>();

    gs.registerHook("check", () -> statesDuringDrain.add(gs.isShuttingDown()));

    Thread.ofVirtual().start(gs::executeShutdown);

    // Awaitility polls instead of fragile Thread.sleep
    var shuttingDown = new AtomicBoolean();
    await().atMost(5, SECONDS).untilAsserted(() -> {
      shuttingDown.set(gs.isShuttingDown());
      assertThat(shuttingDown).isTrue();
    });

    await().atMost(5, SECONDS).untilAsserted(() ->
        assertThat(statesDuringDrain).containsExactly(true));
  }

  @Test void concurrentShutdown_hooksExecuteExactlyOnce() throws Exception {
    int threads = 8;
    var gs = new GracefulShutdown(Duration.ofSeconds(10));
    var hookCount = new AtomicInteger(0);
    gs.registerHook("counter", hookCount::incrementAndGet);

    var barrier = new CyclicBarrier(threads);
    var vThreads = new Thread[threads];
    for (int t = 0; t < threads; t++) {
      vThreads[t] = Thread.ofVirtual().start(() -> {
        try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
        gs.executeShutdown();
      });
    }
    for (var vt : vThreads) { vt.join(10_000); }

    // AtomicBoolean CAS guarantees exactly one thread runs hooks
    assertThat(hookCount.get()).isEqualTo(1);
    assertThat(gs.isShuttingDown()).isTrue();
  }

  // ── Exhaustive concurrency & edge-case tests ─────────────────────────────

  @Nested
  class EdgeCases {

    @Test void shutdownWithNoHooks_completesCleanly() {
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10));
        gs.executeShutdown();
        assertThat(gs.isShuttingDown()).isTrue();
      });
    }

    @Test void allHooksThrow_shutdownCompletes() {
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10));
        gs.registerHook("fail1", () -> { throw new RuntimeException("boom1"); });
        gs.registerHook("fail2", () -> { throw new RuntimeException("boom2"); });
        gs.registerHook("fail3", () -> { throw new RuntimeException("boom3"); });

        gs.executeShutdown();

        assertThat(gs.isShuttingDown()).isTrue();
      });
    }

    @Test void negativeDrainDelay_treatedAsZero() {
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10), Duration.ofMillis(-100));
        var executed = new ArrayList<String>();
        gs.registerHook("hook", () -> executed.add("ran"));

        long before = System.nanoTime();
        gs.executeShutdown();
        long elapsedMs = (System.nanoTime() - before) / 1_000_000;

        assertThat(executed).containsExactly("ran");
        // Should not have paused — negative delay is skipped
        assertThat(elapsedMs).isLessThan(500);
      });
    }

    @Test void hookCallsExecuteShutdown_noReEntryOrDeadlock() {
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10));
        var order = new ArrayList<String>();

        gs.registerHook("first", () -> order.add("first"));
        gs.registerHook("recursive", () -> {
          // Re-entrant call — CAS returns false, immediate no-op
          gs.executeShutdown();
          order.add("recursive");
        });
        gs.registerHook("third", () -> order.add("third"));

        gs.executeShutdown();

        // LIFO: third → recursive (calls executeShutdown → no-op) → first
        assertThat(order).containsExactly("third", "recursive", "first");
        assertThat(gs.isShuttingDown()).isTrue();
      });
    }
  }

  @Nested
  class ConcurrencyStress {

    @Test void concurrentShutdownWithDrain_exactlyOneExecutesHooks() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        int threads = 16;
        var gs = new GracefulShutdown(Duration.ofSeconds(5), Duration.ofMillis(50));
        var hookCount = new AtomicInteger(0);
        gs.registerHook("counter", hookCount::incrementAndGet);

        var barrier = new CyclicBarrier(threads);
        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
            gs.executeShutdown();
          });
        }
        for (var vt : vThreads) { vt.join(10_000); }

        assertThat(hookCount.get()).isEqualTo(1);
      });
    }

    @Test void concurrentIsShuttingDown_allEventuallySeeTrue() throws Exception {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10));
        int readers = 8;
        var allSawTrue = new AtomicInteger(0);
        var barrier = new CyclicBarrier(readers + 1); // +1 for shutdown thread

        // Readers poll isShuttingDown
        var readerThreads = new Thread[readers];
        for (int t = 0; t < readers; t++) {
          readerThreads[t] = Thread.ofVirtual().start(() -> {
            try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
            // Spin until we see true — AtomicBoolean guarantees visibility
            while (!gs.isShuttingDown()) { Thread.onSpinWait(); }
            allSawTrue.incrementAndGet();
          });
        }

        // Shutdown thread
        var shutdownThread = Thread.ofVirtual().start(() -> {
          try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
          gs.executeShutdown();
        });

        for (var rt : readerThreads) { rt.join(10_000); }
        shutdownThread.join(10_000);

        assertThat(allSawTrue.get()).isEqualTo(readers);
      });
    }

    @Test void concurrentShutdownWithSlowHook_timeoutStillEnforced() {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
        var gs = new GracefulShutdown(Duration.ofMillis(100));
        var executed = new ArrayList<String>();

        gs.registerHook("fast1", () -> executed.add("fast1"));
        gs.registerHook("fast2", () -> executed.add("fast2"));
        gs.registerHook("slow", () -> {
          try { Thread.sleep(200); } catch (InterruptedException ignored) {}
          executed.add("slow");
        });

        int threads = 4;
        var barrier = new CyclicBarrier(threads);
        var vThreads = new Thread[threads];
        for (int t = 0; t < threads; t++) {
          vThreads[t] = Thread.ofVirtual().start(() -> {
            try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
            gs.executeShutdown();
          });
        }
        for (var vt : vThreads) { vt.join(10_000); }

        // Only one thread runs hooks. LIFO: slow runs first (200ms > 100ms timeout),
        // then deadline exceeded → remaining fast hooks skipped
        assertThat(executed).containsExactly("slow");
      });
    }

    @Test void hookCallsShutdownFromAnotherThread_noDeadlock() {
      assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
        var gs = new GracefulShutdown(Duration.ofSeconds(10));
        var order = new ArrayList<String>();

        gs.registerHook("first", () -> order.add("first"));
        gs.registerHook("spawner", () -> {
          // Spawn a thread that tries to call executeShutdown
          var t = Thread.ofVirtual().start(gs::executeShutdown);
          try { t.join(2_000); } catch (InterruptedException ignored) {}
          order.add("spawner");
        });

        gs.executeShutdown();

        assertThat(order).containsExactly("spawner", "first");
      });
    }
  }
}