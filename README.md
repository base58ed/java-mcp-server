# Java MCP Server Template

Production-ready [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server template. Lean, framework-free stack: **Undertow + Jackson 3.1 + MCP Java SDK + Apache HttpClient 5 + Failsafe + OTel SDK + Log4j2**. No framework magic — every line readable and ownable.

Clone, add your MCP tools, ship.

## Features

- **MCP Protocol**: Full Streamable HTTP transport (POST/GET/DELETE) with session management
- **Virtual Threads**: Every request dispatched to a virtual thread — optimal I/O scalability
- **Result\<T\>**: Sealed algebraic error handling — no exceptions for control flow
- **Resilience**: Failsafe retry + circuit breaker on all external calls
- **Observability**: OTel traces + Prometheus metrics, conditional enable (zero overhead when off)
- **Production Ready**: Health probes, graceful shutdown, CORS, Kubernetes manifests
- **TOML Config**: Layered configuration (`config.toml` → `.env` → env vars)

## Quick Start

### Prerequisites

- Java 25+
- Maven 3.9+
- Docker (optional)

### Get a Weather API Key

This template uses [WeatherAPI.com](https://www.weatherapi.com/) as an example external API.

1. Go to [weatherapi.com/signup.aspx](https://www.weatherapi.com/signup.aspx)
2. Create a free account (1M calls/month free tier)
3. Copy your API key from the dashboard

### Run Locally

```bash
# Set Weather API key
export WEATHER_API_KEY=your_api_key_here

# Build
mvn package -DskipTests

# Run
java --enable-preview -jar target/java-mcp-server-1.0.0.jar
```

Access points:
- **Health**: http://localhost:8181/health/live
- **Metrics**: http://localhost:8181/metrics
- **MCP**: http://localhost:8181/mcp

## Testing

```bash
# Unit tests
mvn test

# Unit + integration tests
mvn verify

# Test coverage report
mvn verify   # Report at: target/site/jacoco/index.html
```

### Test with curl

```bash
# Health check
curl http://localhost:8181/health/live

# Prometheus metrics
curl http://localhost:8181/metrics

# MCP initialize
curl -X POST http://localhost:8181/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

### Test with MCP Inspector

```bash
npx @modelcontextprotocol/inspector --url http://localhost:8181/mcp
```

Or use the included script:

```bash
./scripts/mcp-inspector.sh
```

## Build & Run

### Production Build

```bash
mvn package
java --enable-preview -jar target/java-mcp-server-1.0.0.jar
```

### Docker

```bash
# Build
mvn package -DskipTests
docker build -f app.Dockerfile -t java-mcp-server:latest .

# Run
docker run -d \
  --name java-mcp-server \
  -p 8181:8181 \
  -e WEATHER_API_KEY=your_api_key_here \
  java-mcp-server:latest

# Verify
curl -s http://localhost:8181/health/live | jq .

# Smoke tests
./scripts/smoke_test.sh http://localhost:8181

# Logs
docker logs -f java-mcp-server

# Stop
docker stop java-mcp-server && docker rm java-mcp-server
```

### Kubernetes

```bash
# Create secrets
kubectl create secret generic mcp-server-secrets \
    --from-literal=weather-api-key=your_key

# Deploy (dev or prod overlay)
kubectl apply -k k8s/overlays/dev
```

## AI Client Integration

### Claude Code

```bash
claude mcp add java-mcp-server --transport streamable-http --url http://localhost:8181/mcp
```

Or add to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "java-mcp-server": {
      "type": "streamable-http",
      "url": "http://localhost:8181/mcp"
    }
  }
}
```

### JetBrains AI Assistant

**Settings** → **Tools** → **AI Assistant** → **Model Context Protocol (MCP)** → **Add**:

| Field | Value |
|-------|-------|
| Name | `java-mcp-server` |
| Transport | `Streamable HTTP` |
| URL | `http://localhost:8181/mcp` |

---

# MCP Development Guide

## Adding Tools

Tools are functions that AI clients invoke. Each tool is a `SyncToolSpecification` combining a JSON schema with a handler lambda.

### Tool Implementation Pattern

```java
public final class YourTools {
  private final YourApiClient client;

  public YourTools(YourApiClient client) {
    this.client = Objects.requireNonNull(client);
  }

  public List<SyncToolSpecification> all() {
    return List.of(yourTool());
  }

  private SyncToolSpecification yourTool() {
    // 1. Define JSON schema for parameters
    var schema = new McpSchema.JsonSchema("object",
        Map.of(
            "query", Map.of("type", "string", "description", "Search query"),
            "limit", Map.of("type", "integer", "description", "Max results (1-50, default: 10)")
        ),
        List.of("query"),  // required fields
        null, null, null);

    // 2. Build tool metadata
    var tool = McpSchema.Tool.builder()
        .name("searchItems")
        .description("Search for items matching a query")
        .inputSchema(schema)
        .build();

    // 3. Handler: validate → call API → return result
    return new SyncToolSpecification(tool, (exchange, request) -> {
      var args = request.arguments();
      var query = str(args.get("query"));

      if (query.isBlank()) {
        return errorResult("Query is required");
      }

      return switch (client.search(query)) {
        case Result.Ok(var data) -> successResult(new SearchResult(query, data));
        case Result.Err(var f) -> errorResult(f.message());
      };
    });
  }

  private static CallToolResult successResult(Object data) { /* JSON serialize */ }
  private static CallToolResult errorResult(String msg) { /* isError=true */ }
  private static String str(Object v) { return v != null ? v.toString() : ""; }
}
```

### Registering Tools

Tools are registered in `McpServerFactory.create()`:

```java
var yourClient = new YourApiClient(httpClient, policies, mapper, config);
var yourTools = new YourTools(yourClient);

var server = McpServer.sync(transport)
    .serverInfo(new McpSchema.Implementation(name, version))
    .tools(weatherTools.all())    // existing
    .tools(yourTools.all())       // yours
    .build();
```

### Tool Design Principles

1. **Single responsibility** — one tool per operation, not one tool with mode switches
2. **Descriptive names** — verb-noun (`getCurrentWeather`, `searchLocations`)
3. **Clear parameter descriptions** — include valid values, ranges, defaults
4. **Structured return types** — records with well-named fields, not raw strings
5. **Graceful errors** — return `errorResult()` with context, never throw

## Adding HTTP Clients

Each external API gets a concrete client class using Apache HttpClient 5 + Failsafe.

### Client Implementation Pattern

```java
public final class YourApiClient {
  private final CloseableHttpClient http;
  private final RetryPolicy<Object> retry;
  private final CircuitBreaker<Object> breaker;
  private final JsonMapper mapper;
  private final String baseUrl;

  public YourApiClient(CloseableHttpClient http, RetryPolicy<Object> retry,
                       CircuitBreaker<Object> breaker, JsonMapper mapper,
                       Config.ClientConfig config) {
    this.http = http;
    this.retry = retry;
    this.breaker = breaker;
    this.mapper = mapper;
    this.baseUrl = config.baseUrl();
  }

  public Result<YourResponse> getData(String query) {
    return Result.of(() -> Failsafe.with(retry, breaker).get(() -> {
      var url = baseUrl + "/v1/data?q=" + URLEncoder.encode(query, UTF_8);
      return http.execute(new HttpGet(url), response -> {
        if (response.getCode() >= 400) {
          throw new IOException("HTTP " + response.getCode());
        }
        return mapper.readValue(response.getEntity().getContent(), YourResponse.class);
      });
    }));
  }
}
```

### Configuration

Add a client section to `config.toml`:

```toml
[client.your-api]
base-url = "https://api.example.com"
connect-timeout-ms = 5000
response-timeout-ms = 10000
pool-size = 20
```

Access via `config.client().get("your-api")`.

### Response Records

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record YourResponse(
    String id,
    @JsonProperty("created_at") Instant createdAt,
    List<Item> items
) {
  public record Item(String name, int value) {}
}
```

## API Evolution

MCP tools form a contract with AI clients. Breaking changes confuse models and break workflows.

### Safe Changes (Additive)

- Add optional parameter with default handling (`null` → default)
- Add new field to response record (clients ignore unknown fields)
- Add new tool

### Breaking Changes (Require Versioning)

| Change | Solution |
|--------|----------|
| Rename tool | Create new tool, deprecate old |
| Remove parameter | Keep accepting, ignore |
| Change parameter type | New tool version |
| Remove response field | Keep field, populate default |

For breaking changes, create versioned tools:

```java
// Keep old tool (delegate to new)
private SyncToolSpecification getWeather() { /* delegates to getWeatherV2 */ }

// New tool with breaking changes
private SyncToolSpecification getWeatherV2() { /* new implementation */ }
```

## Project Structure

```
src/main/java/io/mcpbridge/mcp/
├── Main.java                # Composition root — wires everything
├── config/                  # TOML config loading + typed records
├── server/                  # Undertow server, MCP transport, CORS
├── tool/                    # MCP tool implementations
├── client/                  # HTTP clients + response DTOs
├── common/                  # Result<T>, Failure, ToolResult, TraceIdGen
├── resilience/              # Failsafe policy factories
├── observability/           # OTel traces, metrics, correlation IDs
├── health/                  # Health check endpoints
└── lifecycle/               # Graceful shutdown coordination
```

See [architecture.md](architecture.md) for component diagrams and data flow.

## Configuration

All configuration lives in `config.toml`. Secrets go in `.env` or env vars.

**Precedence**: env var > `.env` file > `config.toml`

```toml
[server]
host = "0.0.0.0"
port = 8181
shutdown-timeout-seconds = 30

[server.cors]
enabled = true
origins = ["*"]
methods = ["GET", "POST", "OPTIONS"]

[mcp]
name = "java-mcp-server"
version = "1.0.0"

[client.weather-api]
base-url = "https://api.weatherapi.com"
connect-timeout-ms = 5000
response-timeout-ms = 10000
pool-size = 20

[resilience]
max-retries = 3
retry-delay-ms = 500
circuit-breaker-failure-threshold = 5
circuit-breaker-delay-ms = 30000

[telemetry]
enabled = false

[telemetry.traces]
enabled = false
endpoint = "http://localhost:4318/v1/traces"
sampler-ratio = 1.0

[telemetry.metrics]
enabled = false
path = "/metrics"

[logging]
level = "INFO"
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `WEATHER_API_KEY` | WeatherAPI.com API key (secret) |

All other config uses `config.toml`. Override any TOML value via env var or `.env` file.

## Observability

### Health Endpoints

| Endpoint | Purpose |
|----------|---------|
| `/health/live` | Liveness probe (is process alive?) |
| `/health/ready` | Readiness probe (can serve traffic?) |
| `/health/started` | Startup probe (initial setup complete?) |

### Metrics

Prometheus metrics at `/metrics` (when `telemetry.metrics.enabled = true`):

| OTel Instrument | Prometheus Name | Type | Labels | Purpose |
|-----------------|-----------------|------|--------|---------|
| `mcp.tool.calls` | `mcp_tool_calls_total` | Counter | `tool`, `status` | Tool invocation count |
| `mcp.tool.duration` | `mcp_tool_duration_seconds` | Histogram | `tool` | Latency distribution |

```promql
# Error rate by tool
sum(rate(mcp_tool_calls_total{status="error"}[5m])) by (tool)
  / sum(rate(mcp_tool_calls_total[5m])) by (tool)

# p95 latency
histogram_quantile(0.95, sum(rate(mcp_tool_duration_seconds_bucket[5m])) by (le, tool))

# Tool usage ranking
topk(10, sum(rate(mcp_tool_calls_total[1h])) by (tool))
```

### Distributed Tracing

Traces exported to an OTLP-compatible backend (Tempo, Jaeger) when `telemetry.traces.enabled = true`. Each request gets an OTel span via `TracingHandler`. Correlation IDs propagate through `ScopedValue` + Log4j2 MDC.

When telemetry is disabled, a noop OTel instance is used — zero overhead.

### Alerting Rules

```yaml
groups:
  - name: mcp-server
    rules:
      - alert: McpToolHighErrorRate
        expr: |
          sum(rate(mcp_tool_calls_total{status="error"}[5m])) by (tool)
          / sum(rate(mcp_tool_calls_total[5m])) by (tool) > 0.1
        for: 5m
        labels:
          severity: warning

      - alert: McpToolHighLatency
        expr: |
          histogram_quantile(0.95, sum(rate(mcp_tool_duration_seconds_bucket[5m])) by (le, tool)) > 5
        for: 5m
        labels:
          severity: warning

      - alert: McpServerUnhealthy
        expr: up{job="mcp-server"} == 0
        for: 1m
        labels:
          severity: critical
```

## Links

- [MCP Specification](https://spec.modelcontextprotocol.io/)
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)
- [Undertow](https://undertow.io/)
- [Jackson 3.1](https://github.com/FasterXML/jackson)
- [Apache HttpClient 5](https://hc.apache.org/httpcomponents-client-5.4.x/)
- [Failsafe](https://failsafe.dev/)
- [WeatherAPI Docs](https://www.weatherapi.com/docs/)
