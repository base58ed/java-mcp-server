#!/bin/bash
# MCP Server Smoke Test
# Verifies Docker container is running and MCP endpoints work

set -e

BASE_URL="${1:-http://localhost:8181}"
VERBOSE="${2:-false}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log() { echo -e "${GREEN}[OK]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

echo "=========================================="
echo "MCP Server Smoke Test"
echo "Target: $BASE_URL"
echo "=========================================="

# Test 1: Health endpoint
echo -n "Testing health endpoint... "
HEALTH=$(curl -sf "${BASE_URL}/health/live" 2>/dev/null || echo "FAILED")
if [[ "$HEALTH" == *"UP"* ]] || [[ "$HEALTH" == *"DOWN"* ]]; then
    log "Health endpoint responding"
else
    fail "Health endpoint not responding"
fi

# Test 2: Metrics endpoint
echo -n "Testing metrics endpoint... "
METRICS=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "FAILED")
if [[ "$METRICS" == *"mcp"* ]] || [[ "$METRICS" == *"target"* ]]; then
    log "Metrics endpoint responding"
else
    warn "Metrics endpoint issue (may be expected)"
fi

# Test 3: MCP Initialize
echo -n "Testing MCP initialize... "
INIT_RESPONSE=$(curl -sf "${BASE_URL}/mcp" -X POST \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}}' \
    2>/dev/null || echo "FAILED")

if [[ "$INIT_RESPONSE" == *"serverInfo"* ]]; then
    log "MCP initialize successful"
    [[ "$VERBOSE" == "true" ]] && echo "$INIT_RESPONSE" | jq .
else
    fail "MCP initialize failed: $INIT_RESPONSE"
fi

# Test 4: Full MCP session (init + notify + tools/list)
echo -n "Testing MCP tools/list... "

# Get session ID
SESSION_ID=$(curl -s -D - "${BASE_URL}/mcp" -X POST \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}}' \
    2>/dev/null | grep -i 'Mcp-Session-Id' | cut -d' ' -f2 | tr -d '\r')

if [[ -z "$SESSION_ID" ]]; then
    fail "Failed to get session ID"
fi

# Send initialized notification
curl -sf "${BASE_URL}/mcp" -X POST \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null 2>&1

# List tools
TOOLS_RESPONSE=$(curl -sf "${BASE_URL}/mcp" -X POST \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
    2>/dev/null || echo "FAILED")

if [[ "$TOOLS_RESPONSE" == *"getCurrentWeather"* ]]; then
    TOOL_COUNT=$(echo "$TOOLS_RESPONSE" | jq -r '.result.tools | length' 2>/dev/null || echo "?")
    log "MCP tools/list returned $TOOL_COUNT tools"
    [[ "$VERBOSE" == "true" ]] && echo "$TOOLS_RESPONSE" | jq .
else
    fail "MCP tools/list failed: $TOOLS_RESPONSE"
fi

# Test 5: Tool call validation
echo -n "Testing tool validation... "
VALIDATION_RESPONSE=$(curl -sf "${BASE_URL}/mcp" -X POST \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H "Mcp-Session-Id: $SESSION_ID" \
    -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getCurrentWeather","arguments":{"city":"","unit":"celsius"}}}' \
    2>/dev/null || echo "FAILED")

if [[ "$VALIDATION_RESPONSE" == *"VALIDATION"* ]]; then
    log "Tool validation working"
    [[ "$VERBOSE" == "true" ]] && echo "$VALIDATION_RESPONSE" | jq .
else
    warn "Tool validation response unexpected"
fi

echo "=========================================="
echo -e "${GREEN}All smoke tests passed!${NC}"
echo "=========================================="
