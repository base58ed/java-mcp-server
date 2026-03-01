package io.mcpbridge.mcp.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class FailureTest {

  @Test void validation() {
    var f = Failure.validation("bad input");
    assertThat(f.code()).isEqualTo("VALIDATION");
    assertThat(f.message()).isEqualTo("bad input");
    assertThat(f.cause()).isNull();
  }

  @Test void notFound() {
    var f = Failure.notFound("gone");
    assertThat(f.code()).isEqualTo("NOT_FOUND");
    assertThat(f.message()).isEqualTo("gone");
  }

  @Test void apiError() {
    var f = Failure.apiError("oops");
    assertThat(f.code()).isEqualTo("API_ERROR");
    assertThat(f.cause()).isNull();
  }

  @Test void apiError_withCause() {
    var cause = new RuntimeException("boom");
    var f = Failure.apiError("oops", cause);
    assertThat(f.code()).isEqualTo("API_ERROR");
    assertThat(f.cause()).isSameAs(cause);
  }

  @Test void timeout() {
    assertThat(Failure.timeout("slow").code()).isEqualTo("TIMEOUT");
  }

  @Test void unavailable() {
    assertThat(Failure.unavailable("down").code()).isEqualTo("UNAVAILABLE");
  }

  @Test void fromException_withMessage() {
    var f = Failure.fromException(new IllegalArgumentException("bad arg"));
    assertThat(f.code()).isEqualTo("IllegalArgumentException");
    assertThat(f.message()).isEqualTo("bad arg");
    assertThat(f.cause()).isNotNull();
  }

  @Test void fromException_nullMessage_usesDefault() {
    var f = Failure.fromException(new NullPointerException());
    assertThat(f.message()).isEqualTo("No message");
  }

  @Test void toException_withCause_wrapsCause() {
    var cause = new RuntimeException("root");
    var f = new Failure("CODE", "msg", cause);
    var ex = f.toException();
    assertThat(ex.getMessage()).isEqualTo("msg");
    assertThat(ex.getCause()).isSameAs(cause);
  }

  @Test void toException_noCause_includesCode() {
    var f = new Failure("CODE", "msg");
    var ex = f.toException();
    assertThat(ex.getMessage()).contains("[CODE]").contains("msg");
    assertThat(ex.getCause()).isNull();
  }

  @Test void twoArgConstructor_nullCause() {
    var f = new Failure("CODE", "msg");
    assertThat(f.cause()).isNull();
  }
}