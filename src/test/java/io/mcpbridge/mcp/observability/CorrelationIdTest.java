package io.mcpbridge.mcp.observability;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class CorrelationIdTest {

  @Test
  void outsideScope_returnsEmptyString() {
    assertThat(CorrelationId.get()).isEmpty();
  }

  @Test
  void insideScope_returnsSetId() {
    CorrelationId.run("abc-123", () ->
        assertThat(CorrelationId.get()).isEqualTo("abc-123"));
  }

  @Test
  void afterScope_returnsEmptyAgain() {
    CorrelationId.run("test-id", () -> {});
    assertThat(CorrelationId.get()).isEmpty();
  }

  @Test
  void setsLog4j2ThreadContext() {
    CorrelationId.run("log-id", () ->
        assertThat(ThreadContext.get(CorrelationId.LOG_KEY)).isEqualTo("log-id"));

    // Cleaned up after scope
    assertThat(ThreadContext.get(CorrelationId.LOG_KEY)).isNull();
  }

  @Test
  void generate_returnsNonEmptyHexId() {
    var id = CorrelationId.generate();
    assertThat(id).hasSize(16).matches("[0-9a-f]+");
  }
}
