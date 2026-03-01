# Architecture

Lean, framework-free MCP server. Undertow + Jackson 3.1 + MCP Java SDK + Apache HttpClient 5 + Failsafe + OTel SDK + Log4j2. Constructor injection at a single composition root. No annotations, no classpath scanning, no magic.

## Component Diagram

```mermaid
graph TB
    subgraph "Undertow HTTP Server"
        VT["Virtual Thread Dispatch"]
        VT --> CORS["CorsHandler"]
        CORS --> TRACE["TracingHandler<br/><i>OTel spans + correlation ID</i>"]
        TRACE --> ROUTE["RoutingHandler"]

        ROUTE -->|"/health/*"| HEALTH["HealthHandler<br/><i>live / ready / started</i>"]
        ROUTE -->|"/metrics"| METRICS["MetricsHandler<br/><i>Prometheus format</i>"]
        ROUTE -->|"/mcp"| TRANSPORT["UndertowMcpTransport<br/><i>POST: JSON-RPC<br/>GET: SSE stream<br/>DELETE: session cleanup</i>"]
    end

    subgraph "MCP Layer"
        TRANSPORT --> SDK["MCP Java SDK<br/><i>McpSyncServer</i>"]
        SDK --> TOOLS["WeatherTools<br/><i>getCurrentWeather<br/>getForecast<br/>searchLocations</i>"]
    end

    subgraph "Client Layer"
        TOOLS --> CLIENT["WeatherApiClient"]
        CLIENT --> FAILSAFE["Failsafe<br/><i>retry + circuit breaker</i>"]
        FAILSAFE --> HTTP["Apache HttpClient 5<br/><i>connection pooling</i>"]
    end

    subgraph "Infrastructure"
        MAIN["Main.java<br/><i>composition root</i>"] -.->|wires| VT
        MAIN -.->|wires| SDK
        MAIN -.->|wires| CLIENT
        CONFIG["ConfigLoader → Config<br/><i>config.toml + .env + env vars</i>"] -.->|configures| MAIN
        TEL["Telemetry<br/><i>OTel SDK (real or noop)</i>"] -.->|provides| TRACE
        TEL -.->|provides| METRICS
        GS["GracefulShutdown<br/><i>LIFO hooks, timeout</i>"] -.->|coordinates| MAIN
        CID["CorrelationId<br/><i>ScopedValue + MDC</i>"] -.->|threaded by| TRACE
    end

    HTTP -->|HTTPS| EXT["External APIs<br/><i>weatherapi.com</i>"]
```

## Request Lifecycle

```mermaid
sequenceDiagram
    participant C as MCP Client
    participant U as Undertow
    participant T as TracingHandler
    participant MCP as MCP SDK
    participant Tool as WeatherTools
    participant API as WeatherApiClient
    participant Ext as weatherapi.com

    C->>U: POST /mcp (JSON-RPC)
    U->>U: Dispatch to virtual thread
    U->>T: CorsHandler → TracingHandler
    T->>T: Generate correlation ID (ScopedValue)
    T->>T: Start OTel span
    T->>MCP: Route to UndertowMcpTransport

    MCP->>MCP: Parse JSON-RPC request
    MCP->>Tool: tools/call → getCurrentWeather

    Tool->>Tool: Validate input
    Tool->>API: getCurrentWeather("London")
    API->>API: Failsafe retry policy
    API->>Ext: GET /v1/current.json?q=London
    Ext-->>API: 200 OK (JSON)
    API->>API: Jackson deserialize → WeatherResponse
    API-->>Tool: Result.Ok(WeatherResponse)

    Tool->>Tool: Map to WeatherResult record
    Tool-->>MCP: CallToolResult (JSON)

    MCP-->>T: JSON-RPC response
    T->>T: End span, record metrics
    T-->>C: 200 OK + Mcp-Session-Id
```

## Package Structure

```
io.mcpbridge.mcp/
├── Main.java                         # Composition root — wires everything
├── config/
│   ├── Config.java                   # Typed config records (server, client, telemetry, etc.)
│   └── ConfigLoader.java            # Layered TOML + .env + env var loader
├── server/
│   ├── UndertowServer.java          # Server builder + handler chain
│   ├── UndertowMcpTransport.java    # Streamable HTTP transport for Undertow
│   ├── McpServerFactory.java        # MCP SDK wiring + tool registration
│   └── CorsHandler.java             # CORS middleware
├── tool/
│   └── WeatherTools.java            # Tool implementations (builder pattern, no annotations)
├── client/
│   ├── HttpClientFactory.java       # Apache HttpClient 5 builder from config
│   ├── WeatherApiClient.java        # Concrete client with Failsafe resilience
│   └── model/                       # Response DTOs (records)
├── common/
│   ├── Result.java                  # Sealed interface: Ok | Err
│   ├── Failure.java                 # Error representation
│   ├── ToolResult.java              # MCP response wrapper
│   └── TraceIdGen.java              # Fast hex trace ID generation
├── resilience/
│   └── Policies.java                # Failsafe retry + circuit breaker factories
├── observability/
│   ├── Telemetry.java               # OTel SDK setup (real or noop based on config)
│   ├── McpMetrics.java              # MCP-specific counters + histograms
│   ├── TracingHandler.java          # Undertow handler wrapper for server spans
│   ├── MetricsHandler.java          # GET /metrics → Prometheus format
│   └── CorrelationId.java           # ScopedValue + Log4j2 ThreadContext
├── health/
│   └── HealthHandler.java           # /health/live, /health/ready, /health/started
└── lifecycle/
    └── GracefulShutdown.java        # Shutdown hook coordination (LIFO, timeout)
```

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| HTTP server | Undertow | Fastest embedded Java server, composition-based handlers, virtual thread dispatch |
| JSON | Jackson 3.1 (`tools.jackson`) | MethodHandle-based, built-in java.time, immutable JsonMapper |
| MCP protocol | MCP Java SDK + custom Undertow transport | SDK handles protocol correctness; Reactor stays at transport boundary |
| HTTP client | Apache HttpClient 5 | Connection pooling, granular timeouts, response handlers |
| Resilience | Failsafe 3.3.2 | Zero transitive deps, builder API, virtual-thread safe |
| Observability | OTel SDK (manual) | Unified traces + metrics, conditional enable, Prometheus /metrics |
| Logging | Log4j2 + Disruptor | Garbage-free async logging |
| Config | TOML layered | `config.toml` → `.env` → env vars. Typed records via Jackson. |
| DI | Constructor injection | No framework. Composition root wires everything in Main.java. |
| Correlation | `ScopedValue` | Virtual-thread friendly, bounded lifetime, no ThreadLocal leak |

## Config Layering

```
env var (highest priority)
  ↓ overrides
.env file
  ↓ overrides
config.toml (lowest priority, defaults)
```

Secrets (API keys) go in `.env` or env vars. Everything else lives in `config.toml`.
