package io.mcpbridge.mcp.common;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * # Result Type for Explicit Error Handling
 *
 * Sealed interface for representing success/failure without exceptions.
 * Use this instead of throwing exceptions for expected error conditions.
 *
 * ## Usage
 *
 * ```java
 * Result<User> result = findUser(id);
 * return switch (result) {
 *   case Result.Ok(var user) -> "Found: " + user.name();
 *   case Result.Err(var failure) -> "Error: " + failure.message();
 * };
 * ```
 */
public sealed interface Result<T> permits Result.Ok, Result.Err {

  record Ok<T>(T value) implements Result<T> {
    public Ok {
      assert value != null : "Ok value must not be null";
    }
  }

  record Err<T>(Failure failure) implements Result<T> {
    public Err {
      assert failure != null : "Err failure must not be null";
    }
  }

  /** Represents a successful void operation. */
  enum Unit { INSTANCE }

  static <T> Result<T> ok(T value) {
    return new Ok<>(value);
  }

  static Result<Unit> success() {
    return new Ok<>(Unit.INSTANCE);
  }

  static <T> Result<T> err(String code, String message) {
    return new Err<>(new Failure(code, message, null));
  }

  static <T> Result<T> err(String code, String message, Throwable cause) {
    return new Err<>(new Failure(code, message, cause));
  }

  static <T> Result<T> err(Failure failure) {
    return new Err<>(failure);
  }

  /** Wraps a throwing supplier into Result. */
  static <T> Result<T> of(ThrowingSupplier<T> supplier) {
    try {
      return ok(supplier.get());
    } catch (Exception e) {
      return new Err<>(Failure.fromException(e));
    }
  }

  default <U> Result<U> map(Function<T, U> f) {
    return switch (this) {
      case Ok(var v) -> new Ok<>(f.apply(v));
      case Err(var failure) -> new Err<>(failure);
    };
  }

  default <U> Result<U> flatMap(Function<T, Result<U>> f) {
    return switch (this) {
      case Ok(var v) -> f.apply(v);
      case Err(var failure) -> new Err<>(failure);
    };
  }

  default T orElse(T defaultValue) {
    return switch (this) {
      case Ok(var v) -> v;
      case Err(var ignored) -> defaultValue;
    };
  }

  default T orElseGet(Supplier<T> supplier) {
    return switch (this) {
      case Ok(var v) -> v;
      case Err(var ignored) -> supplier.get();
    };
  }

  default T orElseThrow() {
    return switch (this) {
      case Ok(var v) -> v;
      case Err(var f) -> throw f.toException();
    };
  }

  /**
   * Unwraps Ok value or throws. Use in tests or when certain of success.
   */
  default T unwrap() {
    return switch (this) {
      case Ok(var v) -> v;
      case Err(var f) -> throw new IllegalStateException("unwrap() on Err: " + f.message());
    };
  }

  /** Unwraps Ok value or throws with custom message. */
  default T expect(String msg) {
    return switch (this) {
      case Ok(var v) -> v;
      case Err(var f) -> throw new IllegalStateException(msg + ": " + f.message());
    };
  }

  default boolean isOk() {
    return this instanceof Ok;
  }

  default boolean isErr() {
    return this instanceof Err;
  }

  @FunctionalInterface
  interface ThrowingSupplier<T> {
    T get() throws Exception;
  }
}
