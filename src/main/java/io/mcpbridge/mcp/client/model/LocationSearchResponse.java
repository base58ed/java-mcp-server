package io.mcpbridge.mcp.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationSearchResponse(
    long id, String name, String region, String country,
    double lat, double lon) {}
