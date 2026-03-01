package io.mcpbridge.mcp;

import io.mcpbridge.mcp.config.Config;
import io.mcpbridge.mcp.config.ConfigLoader;
import io.mcpbridge.mcp.lifecycle.GracefulShutdown;
import io.mcpbridge.mcp.observability.ClientMetrics;
import io.mcpbridge.mcp.observability.CorrelationId;
import io.mcpbridge.mcp.observability.MetricsHandler;
import io.mcpbridge.mcp.observability.Telemetry;
import io.mcpbridge.mcp.server.McpServerFactory;
import io.mcpbridge.mcp.server.UndertowServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/// Entry point — loads config, wires components, starts server.
public final class Main {

  private static final Logger log = LogManager.getLogger();

  void main() {
    var configPath = Path.of("config.toml");
    var config = Files.exists(configPath)
        ? ConfigLoader.loadAs(configPath, Config.class)
            .expect("Failed to parse " + configPath)
        : new Config(null, null, null, null, null, null);
    log.info("config loaded from {}", Files.exists(configPath) ? configPath.toAbsolutePath() : "defaults");

    // Apply TOML log levels — overrides log4j2.xml defaults
    Configurator.setRootLevel(Level.toLevel(config.logging().level()));
    config.logging().categories().forEach((name, lvl) ->
        Configurator.setLevel(name, Level.toLevel(lvl)));

    var shutdown = new GracefulShutdown(
        Duration.ofSeconds(config.server().shutdownTimeoutSeconds()),
        Duration.ofMillis(config.server().drainDelayMs()));

    var telemetry = Telemetry.create(config.telemetry(),
        config.mcp().name(), config.mcp().version());

    var clientMetrics = new ClientMetrics(telemetry.meter());
    var envPath = Path.of(".env");
    var secrets = ConfigLoader.loadSecrets(envPath);
    log.info("secrets loaded from {}", Files.exists(envPath) ? envPath.toAbsolutePath() : "environment only");

    CorrelationId.installReactorHook();

    var mcp = McpServerFactory.create(config, secrets, clientMetrics);
    var server = UndertowServer.create(config.server(), shutdown, telemetry);
    mcp.mountRoutes(server.routes());

    if (telemetry.prometheusReader() != null) {
      server.routes().get(config.telemetry().metrics().path(),
          new MetricsHandler(telemetry.prometheusReader()));
    }

    // Compute drain budget: shutdown timeout minus drain delay, leave 5s for other hooks
    long drainBudgetMs = Math.max(0,
        config.server().shutdownTimeoutSeconds() * 1000L
            - config.server().drainDelayMs()
            - 5000);

    // LIFO order: mcp first (closes SSE streams), then undertow (stops server), then telemetry (flushes)
    shutdown.registerHook("telemetry", telemetry::close);
    shutdown.registerHook("undertow", () -> server.stop(drainBudgetMs));
    shutdown.registerHook("mcp", mcp::close);
    shutdown.installShutdownHook();
    server.start();

    log.info("{} v{} ready", config.mcp().name(), config.mcp().version());
  }
}
