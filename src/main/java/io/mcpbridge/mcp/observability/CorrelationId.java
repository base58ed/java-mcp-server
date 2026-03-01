package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.common.TraceIdGen;
import org.apache.logging.log4j.ThreadContext;
import reactor.core.scheduler.Schedulers;

/// Request-scoped correlation ID using ScopedValue.
///
/// Usage in handler chain:
/// ```java
/// CorrelationId.run(id, () -> { /* all code here sees CorrelationId.get() */ });
/// ```
///
/// Automatically sets Log4j2 ThreadContext so log patterns can include `%X{correlationId}`.
/// Call {@link #installReactorHook()} once at startup to propagate across Reactor scheduler boundaries.
public final class CorrelationId {

  private CorrelationId() {}

  private static final ScopedValue<String> CURRENT = ScopedValue.newInstance();
  static final String LOG_KEY = "correlationId";

  /// Current correlation ID, or empty string if not in a request scope.
  public static String get() {
    return CURRENT.orElse("");
  }

  /// Generate a new correlation ID.
  public static String generate() {
    return TraceIdGen.hexId();
  }

  /// Run a task within a correlation scope.
  /// Sets both ScopedValue and Log4j2 ThreadContext.
  public static void run(String id, Runnable task) {
    ScopedValue.where(CURRENT, id).run(() -> {
      ThreadContext.put(LOG_KEY, id);
      try {
        task.run();
      } finally {
        ThreadContext.remove(LOG_KEY);
      }
    });
  }

  /// Propagates Log4j2 ThreadContext (MDC) across Reactor scheduler boundaries.
  /// Call once at startup before any Reactor usage.
  public static void installReactorHook() {
    Schedulers.onScheduleHook("mdc-propagation", runnable -> {
      var snapshot = ThreadContext.getImmutableContext();
      if (snapshot.isEmpty()) { return runnable; }
      return () -> {
        ThreadContext.putAll(snapshot);
        try {
          runnable.run();
        } finally {
          ThreadContext.clearAll();
        }
      };
    });
  }
}
