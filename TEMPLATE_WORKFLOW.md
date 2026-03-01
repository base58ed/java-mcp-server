# Working With the Template Repo

This project was created from the `java-mcp-server` template. Your repo tracks the template as a second git remote so you can pull infrastructure updates at any time.

## How It Works

Your repo has two remotes:

```
origin   → your team's repo (you push and pull here)
template → the shared template repo (you only pull from here)
```

```
┌─────────────────────────┐
│  java-mcp-server        │
│  (template)             │
└────────┬────────────────┘
         │  you fetch from here
         ▼
┌─────────────────────────┐
│  your-team-mcp-server   │
│  (origin)               │
│                         │
│  your tools, clients,   │
│  config extensions      │
└─────────────────────────┘
```

## One-Time Setup

### 1. Create your team's repo

Create an empty repo (e.g. `your-org/your-team-mcp-server`).

### 2. Clone the template

```bash
git clone git@bitbucket.org:your-org/java-mcp-server.git your-team-mcp-server
cd your-team-mcp-server
```

### 3. Rewire remotes

```bash
git remote rename origin template
git remote add origin git@bitbucket.org:your-org/your-team-mcp-server.git
git push -u origin main
```

### 4. Block accidental pushes to the template

```bash
git remote set-url --push template no_push
```

### 5. Verify

```bash
git remote -v
```

You should see:

```
origin    git@bitbucket.org:your-org/your-team-mcp-server.git (fetch)
origin    git@bitbucket.org:your-org/your-team-mcp-server.git (push)
template  git@bitbucket.org:your-org/java-mcp-server.git      (fetch)
template  no_push                                              (push)
```

## Pulling Template Updates

When notified of a template update (or anytime you want to check):

```bash
git fetch template
git merge template/main
```

That's it. If there are no conflicts, you're done.

## What You Own vs. What the Template Owns

**Add your code here** — these packages and directories are yours:

| Location | Purpose |
|----------|---------|
| `src/main/java/.../tool/` | Your MCP tools |
| `src/main/java/.../client/` | Your HTTP clients and DTOs |
| `config.toml` | Extend with your `[client.*]` sections |
| `.env` | Your API keys and secrets |
| `k8s/` | Your namespace, image, replicas |

**Don't edit these** — the template maintains them:

| Location | Purpose |
|----------|---------|
| `src/main/java/.../Main.java` | Composition root |
| `src/main/java/.../server/` | Undertow server, MCP transport, CORS |
| `src/main/java/.../common/` | `Result`, `Failure`, `ToolResult`, `TraceIdGen` |
| `src/main/java/.../config/` | `Config`, `ConfigLoader` |
| `src/main/java/.../observability/` | `Telemetry`, `McpMetrics`, `TracingHandler`, `CorrelationId` |
| `src/main/java/.../resilience/` | `Policies` (Failsafe factories) |
| `src/main/java/.../lifecycle/` | `GracefulShutdown` |
| `src/main/java/.../health/` | `HealthHandler` |
| `pom.xml` | Dependencies and build (add yours, don't remove template entries) |

**Why this matters**: If you only add new files in your packages and leave template-owned files alone, `git merge template/main` will always be clean — no conflicts.

## Where to Build Your MCP Server

The template ships with a working weather example. Replace it with your own API. Here's what goes where.

### `client/` — HTTP Clients

Each external API gets a concrete client class using Apache HttpClient 5 + Failsafe.

The template provides `WeatherApiClient` as an example. Replace it with your own:

```
client/
├── YourApiClient.java       ← concrete HTTP client
└── model/
    └── YourResponse.java    ← response DTOs (records)
```

Build a client class with constructor injection:

```java
public final class YourApiClient {
  private final CloseableHttpClient http;
  private final RetryPolicy<Object> retry;
  private final CircuitBreaker<Object> breaker;
  private final JsonMapper mapper;
  private final String baseUrl;
  private final String apiKey;

  public YourApiClient(CloseableHttpClient http, RetryPolicy<Object> retry,
                       CircuitBreaker<Object> breaker, JsonMapper mapper,
                       Config.ClientConfig config) {
    this.http = http;
    this.retry = retry;
    this.breaker = breaker;
    this.mapper = mapper;
    this.baseUrl = config.baseUrl();
    this.apiKey = System.getenv("YOUR_API_KEY");
  }

  public Result<YourResponse> getData(String query) {
    return Result.of(() -> Failsafe.with(retry, breaker).get(() -> {
      var url = baseUrl + "/v1/data?key=" + apiKey
          + "&q=" + URLEncoder.encode(query, UTF_8);
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

Configure in `config.toml`:

```toml
[client.your-api]
base-url = "https://api.example.com"
connect-timeout-ms = 5000
response-timeout-ms = 10000
pool-size = 20
```

Use records for DTOs. Add `@JsonIgnoreProperties(ignoreUnknown = true)` to stay resilient to upstream API changes:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record YourResponse(String name, int count) {
  public YourResponse {
    assert name != null && !name.isBlank() : "name required";
    assert count >= 0 : "count must be non-negative";
  }
}
```

### `tool/` — MCP Tools

Tools are the functions that AI agents call. This is the main thing you're building.

```
tool/
└── YourTools.java    ← your tools class
```

Each tool class returns a list of `SyncToolSpecification` — a JSON schema paired with a handler lambda:

```java
public final class YourTools {
  private final YourApiClient client;

  public YourTools(YourApiClient client) {
    this.client = Objects.requireNonNull(client);
  }

  public List<SyncToolSpecification> all() {
    return List.of(searchItems(), getItemDetails());
  }

  private SyncToolSpecification searchItems() {
    var schema = new McpSchema.JsonSchema("object",
        Map.of(
            "query", Map.of("type", "string",
                "description", "Search query"),
            "limit", Map.of("type", "integer",
                "description", "Max results (1-50, default: 10)")
        ),
        List.of("query"),  // required
        null, null, null);

    var tool = McpSchema.Tool.builder()
        .name("searchItems")
        .description("Search for items matching a query. Returns name, category, and price.")
        .inputSchema(schema)
        .build();

    return new SyncToolSpecification(tool, (exchange, request) -> {
      var args = request.arguments();
      var query = str(args.get("query"));

      // 1. Validate
      if (query.isBlank()) {
        return errorResult("Query is required");
      }

      // 2. Call API via Result
      return switch (client.search(query)) {
        case Result.Ok(var items) -> successResult(
            new SearchResult(query, items.size(), items));
        case Result.Err(var f) -> errorResult(f.message());
      };
    });
  }

  // Helper: JSON-serialize success response
  private static CallToolResult successResult(Object data) {
    var json = McpServerFactory.MAPPER.writeValueAsString(data);
    return new CallToolResult(List.of(new McpSchema.TextContent(json)), false);
  }

  // Helper: error response
  private static CallToolResult errorResult(String msg) {
    return new CallToolResult(List.of(new McpSchema.TextContent(msg)), true);
  }

  private static String str(Object v) { return v != null ? v.toString() : ""; }
}
```

### Registering Your Tools

Wire your tools in `McpServerFactory.create()`:

```java
// In McpServerFactory.create():
var yourClient = new YourApiClient(httpClient, retry, breaker, mapper,
    config.client().get("your-api"));
var yourTools = new YourTools(yourClient);

var server = McpServer.sync(transport)
    .serverInfo(new McpSchema.Implementation(name, version))
    .tools(weatherTools.all())   // existing template example
    .tools(yourTools.all())      // your tools
    .build();
```

### Putting It Together

No framework magic — you wire everything explicitly:

```
Main.java (composition root)
├── ConfigLoader.loadAs("config.toml") → Config record
├── HttpClientFactory.create(config)   → CloseableHttpClient
├── Policies.retry(config)             → RetryPolicy
├── Policies.circuitBreaker(config)    → CircuitBreaker
├── new YourApiClient(http, retry, breaker, mapper, config)
├── new YourTools(yourClient)
└── McpServer.sync(transport)
    .tools(yourTools.all())            → registered with MCP SDK
    .build()
```

Add a client section to `config.toml`, create the client class, create the tools class, register in `McpServerFactory`. That's it.

## Contributing — Branch, PR, Merge

Direct pushes to `main` are blocked. All changes go through a pull request.

```
main (protected)
 │
 ├── feature/add-sms-tool
 ├── feature/update-client-model
 └── fix/validation-bug
```

### 1. Create a branch

Always branch from an up-to-date `main`:

```bash
git checkout main
git pull origin main
git checkout -b feature/your-change
```

Use a clear prefix: `feature/`, `fix/`, `refactor/`, `chore/`.

### 2. Make your changes

Work on your branch. Commit as often as you like:

```bash
git add -A
git commit -m "Add SMS send tool with input validation"
```

### 3. Pull template updates before pushing

Before raising a PR, make sure your branch includes the latest from both your team and the template:

```bash
git fetch origin
git rebase origin/main

git fetch template
git merge template/main
```

### 4. Push your branch

```bash
git push origin feature/your-change
```

### 5. Create a pull request

Open a PR in Bitbucket from `feature/your-change` → `main`. Include:

- What you changed and why
- How to test it (or confirm tests pass)
- Any config or environment changes needed

### 6. Review and merge

A maintainer reviews your PR. Once approved, it gets merged into `main`. You don't merge it yourself.

After merge, clean up your local branch:

```bash
git checkout main
git pull origin main
git branch -d feature/your-change
```

### What's enforced on the repo

| Rule | Effect |
|------|--------|
| No direct push to `main` | `git push origin main` is rejected |
| Pull request required | All changes go through a PR |
| Minimum 1 approval | A maintainer must approve before merge |
| No force push to `main` | History rewriting is blocked |
| No deletion of `main` | Branch cannot be deleted |

## If You Get a Merge Conflict

This means a template-owned file was edited locally. In most cases, take the template version:

```bash
git fetch template
git merge template/main
# conflict in src/main/java/.../common/Result.java
git checkout template/main -- src/main/java/io/mcpbridge/mcp/common/Result.java
git add src/main/java/io/mcpbridge/mcp/common/Result.java
git merge --continue
```

If the conflict is in a file you legitimately need to customize (like `pom.xml` for your own dependencies or `config.toml` for your config), resolve it manually — keep both your additions and the template's changes.

## Daily Workflow

```bash
git checkout main
git pull origin main              # get your team's latest
git fetch template                # check for template updates
git merge template/main           # incorporate template updates (if any)
git checkout -b feature/my-work   # start a new branch
# ... do your work ...
git push origin feature/my-work   # push branch, open PR in Bitbucket
```
