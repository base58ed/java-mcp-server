package io.mcpbridge.mcp.common;

/**
 * # Tool Result for MCP Responses
 *
 * Wrapper for tool responses that provides consistent success/error structure
 * for AI clients. This is serialized to JSON and returned to the MCP client.
 *
 * ## Usage
 *
 * ```java
 * @Tool(description = "...")
 * public ToolResult<MyData> myTool(String arg) {
 *   if (arg == null) {
 *     return ToolResult.error("ARG_REQUIRED", "Argument is required");
 *   }
 *   try {
 *     var data = fetchData(arg);
 *     return ToolResult.ok(data);
 *   } catch (Exception e) {
 *     return ToolResult.error("FETCH_FAILED", e.getMessage());
 *   }
 * }
 * ```
 *
 * ## JSON Output
 *
 * Success: `{"success": true, "data": {...}, "error": null}`
 * Failure: `{"success": false, "data": null, "error": {"code": "...", "message": "..."}}`
 */
public record ToolResult<T>(boolean success, T data, ErrorInfo error) {

  public ToolResult {
    assert !(success && error != null) : "success=true cannot have error";
    assert !(!success && data != null) : "success=false cannot have data";
  }

  public static <T> ToolResult<T> ok(T data) {
    assert data != null : "ok data must not be null";
    return new ToolResult<>(true, data, null);
  }

  public static <T> ToolResult<T> error(String code, String message) {
    return new ToolResult<>(false, null, new ErrorInfo(code, message));
  }

  public static <T> ToolResult<T> error(Failure failure) {
    return new ToolResult<>(false, null, new ErrorInfo(failure.code(), failure.message()));
  }

  public static <T> ToolResult<T> fromResult(Result<T> result) {
    return switch (result) {
      case Result.Ok(var value) -> ok(value);
      case Result.Err(var failure) -> error(failure);
    };
  }

  /** Error details for the MCP client. */
  public record ErrorInfo(String code, String message) {
    public ErrorInfo {
      assert code != null && !code.isBlank() : "code required";
      assert message != null : "message required";
    }
  }
}
