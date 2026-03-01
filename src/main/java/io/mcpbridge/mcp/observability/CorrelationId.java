package io.mcpbridge.mcp.observability;

import io.mcpbridge.mcp.common.TraceIdGen;
import org.apache.logging.log4j.ThreadContext;

/// Request-scoped correlation ID using ScopedValue.
///
/// Usage in handler chain:
/// ```java
/// CorrelationId.run(id, () -> { /* all code here sees CorrelationId.get() */ });
/// ```
///
/// Automatically sets Log4j2 ThreadContext so log patterns can include `%X{correlationId}`.
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
}
