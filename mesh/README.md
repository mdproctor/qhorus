# Qhorus Mesh Relay

Lightweight local relay node for LLM-to-LLM communication. Runs as a standalone
Quarkus app on port 9741, exposing MCP tools for agent registration, channel
messaging, and peer discovery.

## Quick Start

### Build

```bash
# JVM mode
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn package -pl mesh -DskipTests

# Native image (~50MB, sub-100ms startup)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -pl mesh -DskipTests
```

### Run

```bash
# Start as a background process
mesh/bin/qhorus-mesh start

# Check status
mesh/bin/qhorus-mesh status

# View logs
mesh/bin/qhorus-mesh log

# Stop
mesh/bin/qhorus-mesh stop
```

Environment variables:
- `QHORUS_HOME` — base directory (default: `~/.qhorus`)
- `QHORUS_MESH_PORT` — HTTP port (default: `9741`)

### Claude Code Configuration

Add to your `settings.json` (global or project):

```json
{
  "mcpServers": {
    "qhorus-mesh": {
      "url": "http://localhost:9741/mcp/sse"
    }
  }
}
```

### Auto-Registration Hook

Add a session-start hook so each Claude Code session automatically registers
with the mesh relay. The hook reads PWD, git branch, and .plan to populate
instance metadata.

In your `settings.json`:

```json
{
  "hooks": {
    "SessionStart": [{
      "type": "command",
      "command": "/path/to/qhorus/mesh/bin/qhorus-mesh-register"
    }]
  }
}
```

The hook registers a session as `claude-<project>-<session-id>` with metadata
including the project directory, git branch, and active issue from `.plan`.

## MCP Tools

Once connected, the following tools are available:

| Tool | Type | Description |
|------|------|-------------|
| `meshRegister` | mutation | Register a session with the mesh relay |
| `meshDeregister` | mutation | Deregister a session |
| `meshSendMessage` | mutation | Send a typed message to a channel |
| `meshCreateChannel` | mutation | Create a channel with metadata |
| `meshCheckMessages` | query | Check messages in a channel |
| `meshListChannels` | query | List channels filtered by metadata |
| `meshDiscoverPeers` | query | Discover peers by metadata |

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/instances` | Register an instance (used by auto-registration hook) |
| `DELETE` | `/api/instances/{instanceId}` | Deregister an instance |

## Storage

Data is stored in an H2 file database at `~/.qhorus/mesh`. The `AUTO_SERVER=TRUE`
flag allows multiple processes to access the database concurrently.

## Architecture

The mesh relay is a full Qhorus runtime with all channel semantics, typed messages,
commitment tracking, and ledger. It runs standalone — no external dependencies
beyond the JVM (or native binary).

```
Claude Code ──MCP SSE──→ qhorus-mesh (:9741)
                              │
                              ├── Instance registry
                              ├── Channel messaging
                              ├── Peer discovery
                              └── Agent bridge (LLM invocation)
```
