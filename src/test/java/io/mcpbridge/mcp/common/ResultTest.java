package io.mcpbridge.mcp.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ResultTest {

  @Nested class Factories {
    @Test void ok_wrapsValue() {
      assertThat(Result.ok("hello").unwrap()).isEqualTo("hello");
    }

    @Test void success_returnsUnitOk() {
      var r = Result.success();
      assertThat(r.isOk()).isTrue();
      assertThat(r.unwrap()).isEqualTo(Result.Unit.INSTANCE);
    }

    @Test void err_codeAndMessage() {
      Result<String> r = Result.err("CODE", "msg");
      assertThat(r.isErr()).isTrue();
    }

    @Test void err_withCause() {
      var cause = new RuntimeException("boom");
      Result<String> r = Result.err("CODE", "msg", cause);
      var failure = ((Result.Err<String>) r).failure();
      assertThat(failure.cause()).isSameAs(cause);
    }

    @Test void err_fromFailure() {
      Result<String> r = Result.err(Failure.validation("bad"));
      assertThat(r.isErr()).isTrue();
    }

    @Test void of_success() {
      assertThat(Result.of(() -> 42).unwrap()).isEqualTo(42);
    }

    @Test void of_exception_wrapsAsErr() {
      var r = Result.of(() -> { throw new RuntimeException("boom"); });
      assertThat(r.isErr()).isTrue();
    }
  }

  @Nested class Transformations {
    @Test void map_ok_transformsValue() {
      assertThat(Result.ok(2).map(n -> n * 3).unwrap()).isEqualTo(6);
    }

    @Test void map_err_propagatesFailure() {
      Result<Integer> r = Result.err("E", "msg");
      assertThat(r.map(n -> n * 3).isErr()).isTrue();
    }

    @Test void flatMap_ok_chainsResults() {
      assertThat(Result.ok(2).flatMap(n -> Result.ok(n * 3)).unwrap()).isEqualTo(6);
    }

    @Test void flatMap_err_propagatesFailure() {
      Result<Integer> r = Result.err("E", "msg");
      assertThat(r.flatMap(n -> Result.ok(n * 3)).isErr()).isTrue();
    }
  }

  @Nested class Extraction {
    @Test void orElse_ok_returnsValue() {
      assertThat(Result.ok("a").orElse("b")).isEqualTo("a");
    }

    @Test void orElse_err_returnsDefault() {
      assertThat(Result.<String>err("E", "m").orElse("b")).isEqualTo("b");
    }

    @Test void orElseGet_ok_returnsValue() {
      assertThat(Result.ok("a").orElseGet(() -> "b")).isEqualTo("a");
    }

    @Test void orElseGet_err_callsSupplier() {
      assertThat(Result.<String>err("E", "m").orElseGet(() -> "b")).isEqualTo("b");
    }

    @Test void orElseThrow_ok_returnsValue() {
      assertThat(Result.ok("a").orElseThrow()).isEqualTo("a");
    }

    @Test void orElseThrow_err_throws() {
      assertThatThrownBy(() -> Result.<String>err("E", "msg").orElseThrow())
          .isInstanceOf(RuntimeException.class);
    }

    @Test void unwrap_ok_returnsValue() {
      assertThat(Result.ok("a").unwrap()).isEqualTo("a");
    }

    @Test void unwrap_err_throws() {
      assertThatThrownBy(() -> Result.<String>err("E", "msg").unwrap())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unwrap() on Err");
    }

    @Test void expect_ok_returnsValue() {
      assertThat(Result.ok("a").expect("fine")).isEqualTo("a");
    }

    @Test void expect_err_throwsWithCustomMessage() {
      assertThatThrownBy(() -> Result.<String>err("E", "msg").expect("oops"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("oops");
    }
  }

  @Nested class Predicates {
    @Test void isOk_true()  { assertThat(Result.ok(1).isOk()).isTrue(); }
    @Test void isOk_false() { assertThat(Result.<Integer>err("E", "m").isOk()).isFalse(); }
    @Test void isErr_true()  { assertThat(Result.<Integer>err("E", "m").isErr()).isTrue(); }
    @Test void isErr_false() { assertThat(Result.ok(1).isErr()).isFalse(); }
  }
}