package io.mcpbridge.mcp.config;

import io.mcpbridge.mcp.common.Result;
import io.mcpbridge.mcp.common.Result.Unit;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.toml.TomlMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/// Layered config: `config.toml` → `.env` → environment variables.
/// Higher layers override lower. All keys are dot-separated (e.g., `server.port`).
public final class ConfigLoader {

  private static final TomlMapper TOML = TomlMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
      .build();
  private final Map<String, String> values;

  private ConfigLoader(Map<String, String> values) {
    this.values = values;
  }

  public static ConfigLoader load(Path tomlPath, Path envPath) {
    var values = new HashMap<String, String>();
    loadToml(tomlPath, values);
    loadEnv(envPath, values);
    System.getenv().forEach(values::put);
    return new ConfigLoader(Map.copyOf(values));
  }

  public static ConfigLoader load(Path tomlPath) {
    return load(tomlPath, Path.of(".env"));
  }

  /// Load secrets from `.env` file, overridden by real environment variables.
  /// Use for non-TOML values like API keys.
  public static Map<String, String> loadSecrets(Path envPath) {
    var values = new HashMap<String, String>();
    loadEnv(envPath, values);
    System.getenv().forEach(values::put);
    return Map.copyOf(values);
  }

  public static <T> Result<T> loadAs(Path tomlPath, Class<T> type) {
    return Result.of(() -> TOML.readValue(tomlPath.toFile(), type));
  }

  public String get(String key) { return values.get(key); }

  public String getOrDefault(String key, String fallback) {
    return values.getOrDefault(key, fallback);
  }

  public int getInt(String key, int fallback) {
    var v = values.get(key);
    return v != null ? Integer.parseInt(v) : fallback;
  }

  public boolean getBool(String key, boolean fallback) {
    var v = values.get(key);
    return v != null ? "true".equalsIgnoreCase(v) : fallback;
  }

  public Map<String, String> all() { return values; }

  @SuppressWarnings("unchecked")
  private static void loadToml(Path path, Map<String, String> out) {
    if (!Files.exists(path)) { return; }
    Result.of(() -> TOML.readValue(path.toFile(), Map.class)).map(map -> {
      flatten("", (Map<String, Object>) map, out);
      return Unit.INSTANCE;
    });
  }

  private static void flatten(String prefix, Map<String, Object> map, Map<String, String> out) {
    map.forEach((k, v) -> {
      var key = prefix.isEmpty() ? k : prefix + "." + k;
      if (v instanceof Map<?, ?> nested) {
        @SuppressWarnings("unchecked") var m = (Map<String, Object>) nested;
        flatten(key, m, out);
      } else {
        out.put(key, String.valueOf(v));
      }
    });
  }

  private static void loadEnv(Path path, Map<String, String> out) {
    if (!Files.exists(path)) { return; }
    Result.of(() -> Files.readAllLines(path)).map(lines -> {
      lines.stream()
          .filter(l -> !l.isBlank() && !l.startsWith("#") && l.contains("="))
          .forEach(l -> {
            var eq = l.indexOf('=');
            out.put(l.substring(0, eq).strip(), l.substring(eq + 1).strip());
          });
      return Unit.INSTANCE;
    });
  }
}