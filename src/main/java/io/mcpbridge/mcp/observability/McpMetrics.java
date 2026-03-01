package io.mcpbridge.mcp.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/// MCP-specific metrics using OTel Meter API.
///
/// Tracks tool invocations and durations.
/// When telemetry is disabled, Meter is noop — all recording calls are zero-cost.
public final class McpMetrics {

  private static final AttributeKey<String> TOOL = AttributeKey.stringKey("mcp.tool");
  private static final AttributeKey<String> STATUS = AttributeKey.stringKey("mcp.status");

  private final LongCounter toolCalls;
  private final DoubleHistogram toolDuration;

  public McpMetrics(Meter meter) {
    assert meter != null : "meter required";

    toolCalls = meter.counterBuilder("mcp.tool.calls")
        .setDescription("Number of MCP tool invocations")
        .setUnit("{calls}")
        .build();

    toolDuration = meter.histogramBuilder("mcp.tool.duration")
        .setDescription("MCP tool call duration")
        .setUnit("s")
        .build();
  }

  /// Record a tool invocation with its outcome.
  public void recordCall(String tool, String status) {
    toolCalls.add(1, Attributes.of(TOOL, tool, STATUS, status));
  }

  /// Record tool execution duration in seconds.
  public void recordDuration(String tool, double seconds) {
    toolDuration.record(seconds, Attributes.of(TOOL, tool));
  }

}
