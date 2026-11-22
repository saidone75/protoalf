# protoalf

[![CI](https://github.com/saidone75/protoalf/actions/workflows/ci.yml/badge.svg)](https://github.com/saidone75/protoalf/actions/workflows/ci.yml)
[![Javadoc](https://github.com/saidone75/protoalf/actions/workflows/publish-javadoc.yml/badge.svg)](https://saidone75.github.io/protoalf/javadoc/)

Alfresco repository services over **gRPC**, with a Java client and an **MCP server** for AI applications.
Work in progress.

## Modules

- `protoalf-platform`: Alfresco 7 repository module exposing gRPC services aligned with Alfresco APIs.
- `protoalf-client`: Java client with ticket authentication and streaming support.
- `protoalf-mcp`: Spring AI MCP server using the gRPC client.
- `examples/java-client`: minimal client application.

The shared contract is in [`proto/protoalf/v1/alfresco.proto`](proto/protoalf/v1/alfresco.proto).
API details are in the [Javadoc](https://saidone75.github.io/protoalf/javadoc/).

## Build and run

Requires **JDK 21**, **Maven** and **Docker Compose**.

```bash
mvn clean package             # Build and test all modules
./run-docker.sh build_start    # Build images and start the development stack
```

The stack includes Alfresco 7 with the platform module, Share, PostgreSQL, Solr, ActiveMQ,
content transforms and the MCP server. gRPC TLS certificates and the MCP user are created automatically.

| Service | Address |
|---|---|
| Alfresco | http://localhost:8080/alfresco |
| Share | http://localhost:8180/share |
| gRPC (TLS) | `localhost:9090` |
| MCP (Streamable HTTP) | http://localhost:8090/mcp |

Alfresco/Share: `admin` / `admin`. MCP repository user: `mcp` / `example`
(override with `MCP_ALFRESCO_PASSWORD`). The MCP endpoint has no authentication; this stack is for local development.

```bash
./run-docker.sh reload_acs     # Rebuild the platform JAR and ACS image, recreate ACS
./run-docker.sh reload_mcp     # Rebuild and recreate MCP
./run-docker.sh status
./run-docker.sh tail
./run-docker.sh stop           # Preserve data volumes
```

`./run-docker.sh --help` lists all commands. `purge` deletes the stack's data volumes.

To connect Claude Code:

```bash
claude mcp add --transport http alfresco http://localhost:8090/mcp
```
