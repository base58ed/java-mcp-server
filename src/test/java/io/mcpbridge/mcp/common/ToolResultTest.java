package io.mcpbridge.mcp.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ToolResultTest {

  @Test void ok_wrapsData() {
    var r = ToolResult.ok("data");
    assertThat(r.success()).isTrue();
    assertThat(r.data()).isEqualTo("data");
    assertThat(r.error()).isNull();
  }

  @Test void error_codeAndMessage() {
    var r = ToolResult.<String>error("CODE", "msg");
    assertThat(r.success()).isFalse();
    assertThat(r.data()).isNull();
    assertThat(r.error().code()).isEqualTo("CODE");
    assertThat(r.error().message()).isEqualTo("msg");
  }

  @Test void error_fromFailure() {
    var r = ToolResult.<String>error(Failure.validation("bad"));
    assertThat(r.success()).isFalse();
    assertThat(r.error().code()).isEqualTo("VALIDATION");
    assertThat(r.error().message()).isEqualTo("bad");
  }

  @Test void fromResult_ok() {
    var r = ToolResult.fromResult(Result.ok("hello"));
    assertThat(r.success()).isTrue();
    assertThat(r.data()).isEqualTo("hello");
  }

  @Test void fromResult_err() {
    var r = ToolResult.<String>fromResult(Result.err("CODE", "msg"));
    assertThat(r.success()).isFalse();
    assertThat(r.error().code()).isEqualTo("CODE");
  }

  @Test void errorInfo_fields() {
    var info = new ToolResult.ErrorInfo("CODE", "msg");
    assertThat(info.code()).isEqualTo("CODE");
    assertThat(info.message()).isEqualTo("msg");
  }
}