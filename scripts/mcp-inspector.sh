#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════════
# MCP Inspector - Test MCP server interactively
# ═══════════════════════════════════════════════════════════════════════════════
# Usage: ./scripts/mcp-inspector.sh [server-url]

set -e

SERVER_URL="${1:-http://localhost:8181/mcp}"

echo "╔═══════════════════════════════════════════════════════════════════════╗"
echo "║                      MCP Inspector                                     ║"
echo "╠═══════════════════════════════════════════════════════════════════════╣"
echo "║ Server: $SERVER_URL"
echo "║                                                                       ║"
echo "║ Commands in Inspector:                                                ║"
echo "║   - tools/list     List available tools                               ║"
echo "║   - tools/call     Call a tool                                        ║"
echo "║   - resources/list List available resources                           ║"
echo "║   - prompts/list   List available prompts                             ║"
echo "╚═══════════════════════════════════════════════════════════════════════╝"
echo ""

# Check if server is running
if ! curl -s -o /dev/null -w "%{http_code}" "$SERVER_URL" | grep -q "200\|405"; then
    echo "⚠️  Server doesn't appear to be running at $SERVER_URL"
    echo "   Start it with: java --enable-preview -jar target/java-mcp-server-1.0.0-SNAPSHOT.jar"
    echo ""
fi

# Launch inspector
npx @modelcontextprotocol/inspector --url "$SERVER_URL"
