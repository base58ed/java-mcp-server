package io.mcpbridge.mcp.common;

/**
 * # Failure Record for Error Details
 *
 * Immutable error representation with code, message, and optional cause.
 *
 * ## Error Codes
 *
 * Use consistent error codes across the application:
 * - `VALIDATION` - Input validation failed
 * - `NOT_FOUND` - Resource not found
 * - `API_ERROR` - External API returned error
 * - `TIMEOUT` - Operation timed out
 * - `UNAVAILABLE` - Service unavailable
 */
public record Failure(String code, String message, Throwable cause) {

  public Failure {
    assert code != null && !code.isBlank() : "code required";
    assert message != null : "message required";
  }

  public Failure(String code, String message) {
    this(code, message, null);
  }

  public static Failure fromException(Throwable t) {
    return new Failure(
        t.getClass().getSimpleName(),
        t.getMessage() != null ? t.getMessage() : "No message",
        t
    );
  }

  public static Failure validation(String message) {
    return new Failure("VALIDATION", message);
  }

  public static Failure notFound(String message) {
    return new Failure("NOT_FOUND", message);
  }

  public static Failure apiError(String message) {
    return new Failure("API_ERROR", message);
  }

  public static Failure apiError(String message, Throwable cause) {
    return new Failure("API_ERROR", message, cause);
  }

  public static Failure timeout(String message) {
    return new Failure("TIMEOUT", message);
  }

  public static Failure unavailable(String message) {
    return new Failure("UNAVAILABLE", message);
  }

  public RuntimeException toException() {
    return cause != null
        ? new RuntimeException(message, cause)
        : new RuntimeException("[" + code + "] " + message);
  }
}
