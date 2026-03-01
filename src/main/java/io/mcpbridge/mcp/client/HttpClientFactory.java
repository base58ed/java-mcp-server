package io.mcpbridge.mcp.client;

import io.mcpbridge.mcp.config.Config;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;

/// Builds Apache HttpClient 5 instances from TOML config.
public final class HttpClientFactory {

  private HttpClientFactory() {}

  public static CloseableHttpClient create(Config.ClientConfig config) {
    var pool = new PoolingHttpClientConnectionManager();
    pool.setMaxTotal(config.poolSize());
    pool.setDefaultMaxPerRoute(config.poolSize());

    return HttpClients.custom()
        .setConnectionManager(pool)
        .setDefaultRequestConfig(RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(config.connectTimeoutMs()))
            .setResponseTimeout(Timeout.ofMilliseconds(config.responseTimeoutMs()))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(config.connectTimeoutMs()))
            .build())
        .build();
  }
}
