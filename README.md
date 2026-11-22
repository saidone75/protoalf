# protoalf

Exposes the Alfresco repository over **gRPC** and, on top of it, as **MCP tools** for LLMs (Claude, Ollama, ...).

```
MCP client (Claude Desktop / Claude Code / Spring AI app with Ollama ...)
        │  MCP, Streamable HTTP  →  http://localhost:8090/mcp
protoalf-mcp        Spring Boot 4.1 + Spring AI 2.0 MCP server
        │  gRPC + TLS, header "alfresco-ticket"
protoalf-platform   JAR module inside alfresco.war (port 9090)
        │
Alfresco repository
```

## Layout

| Path | What |
|---|---|
| `proto/protoalf/v1/alfresco.proto` | The gRPC contract. Single source: both modules generate their own stubs from it |
| `protoalf-platform/` | Alfresco Platform JAR module. `mvn package` produces one shaded JAR (gRPC/Protobuf relocated under `org.saidone.protoalf.platform.internal`). It targets Java 11 and is built against Alfresco 7.0.0, the Docker stack uses Alfresco 7.0.0 |
| `protoalf-client/` | Plain Java 17 library (no Spring): generated stubs, ticket login, channel factory and `NodeServiceClient`. Use it from any Java application |
| `protoalf-mcp/` | Spring Boot app: uses `protoalf-client`, publishes read (`alfresco_search`, `alfresco_get_node`, `alfresco_list_children`, `alfresco_list_models`) and write tools over MCP |
| `examples/java-client/` | Minimal Spring Boot app using `protoalf-client`: logs in, lists Company Home and the last modified documents, then exits |
| `docker/` | Docker Compose for the whole stack, plus the script generating the gRPC TLS certificates |
| `run-docker.sh` | Build Docker images and manage the full development stack (Bash) |
| `run.sh` / `run.bat` | Original build and run helpers |

## Run

```
./run-docker.sh build_start      # Windows: run.bat build_start
```

Starts: Alfresco (with the gRPC module), Share, PostgreSQL, Search Services, ActiveMQ and the MCP server.

| Service | URL |
|---|---|
| Alfresco | http://localhost:8080/alfresco |
| Share | http://localhost:8180/share (admin / admin) |
| gRPC (TLS) | localhost:9090 |
| MCP server | http://localhost:8090/mcp |

Requires Docker Compose and Maven with JDK 21 or later. The script works from any directory and honors `JAVA_HOME` and `M2_HOME`.

`build_start` compiles the platform, client and MCP modules, builds the ACS and MCP images, starts the stack and follows its logs. TLS certificates and the dedicated MCP user are prepared automatically; the transform service handles content conversions. Press Ctrl+C to stop following logs while the stack keeps running.

Other tasks: `build` (Maven packages and Docker images), `start` (uses existing Maven artifacts), `stop`, `restart`, `update` (rebuild and start), `status`, `tail`, `tail_all`, `reload_acs`, `reload_mcp`. `purge` also deletes all stack volumes, including repository data, database, indexes and TLS certificates. Ordinary stops and updates preserve volumes.

## Connect an MCP client

Claude Code:

```
claude mcp add --transport http alfresco http://localhost:8090/mcp
```

Any other MCP client supporting the Streamable HTTP transport can use the same URL.

## Repository services

The platform exposes separate gRPC services corresponding to Alfresco's repository services:

| Alfresco API | Platform adapter | RPC operations |
|---|---|---|
| `AuthenticationService` | `GrpcAuthenticationService` | `Authenticate` (credentials → ticket) |
| `NodeService` | `GrpcNodeService` | `GetProperties`, `AddProperties`, `GetType`, `SetType`, `GetAspects`, `AddAspect`, `RemoveAspect`, `DeleteNode` |
| `FileFolderService` | `GrpcFileFolderService` | `List`, `Create`, `Move`, `Copy` |
| `VersionService` | `GrpcVersionService` | `GetVersionHistory` |
| `ContentService` | `GrpcContentService` | `GetReader`, `GetWriter`, `DownloadContent`, `UploadContent` |
| `SearchService` | `GrpcSearchService` | `Query` |
| `DictionaryService` | `GrpcDictionaryService` | `GetAllModels`, `GetModel` |

Names and parameters follow the corresponding Alfresco operations, using Protobuf messages instead of local Java objects. This is a supported subset of the Alfresco APIs. `GetReader` returns a content slice and `GetWriter` writes the supplied bytes; streaming extensions handle large content. `Authenticate` combines authentication and ticket retrieval. `GetAllModels` supports optional filtering and property expansion; `GetModel` takes a qualified model name.

`NodeService.GetNode` and `UpdateNode` remain convenience extensions for a complete node snapshot and an atomic metadata update. `AddProperties` merges the supplied metadata; it does not replace the whole property map. Write validation and permissions apply to both primitive operations and convenience methods.

`GrpcServiceSupport` centralizes ticket context, transactions, node references and gRPC error mapping. Every service is registered with the same authentication interceptor.

The Java `NodeServiceClient` remains a convenience facade and dispatches each call to the appropriate service. Existing facade method names and MCP tools are unchanged. **The generated gRPC API has changed:** consumers using generated stubs directly must regenerate them and update the service/method names shown above. Rebuild both platform and client together.

## Use the Java client

```xml
<dependency>
    <groupId>org.saidone</groupId>
    <artifactId>protoalf-client</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
ManagedChannel channel = AlfrescoGrpcChannels.create("localhost", 9090, "/path/to/ca.crt", null);
val tickets = new AuthenticationServiceClient(channel, "mcp", "example", Duration.ofSeconds(30));
val client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(30));

Node home = client.getNode("-my-");
SearchResponse docs = client.search("SELECT * FROM cmis:document WHERE cmis:name LIKE 'budget%'", "cmis", 0, 25);

channel.shutdownNow();
```

* `AuthenticationServiceClient` is the client of `AuthenticationService`. Built with user and password it is the
  `TicketProvider` of `NodeServiceClient`: it logs in through `Authenticate` lazily and caches the ticket. On `UNAUTHENTICATED`
  the client logs in again and retries the call once. `login(user, password)` exchanges any credentials for a ticket.
  Everything goes over gRPC: the client never calls Alfresco's REST API.
* `downloadContent(nodeRef, out)` and `uploadContent(header, in)` stream the bytes. `uploadContent` is not retried on
  `UNAUTHENTICATED`, because the input stream cannot be rewound: open it again and call it again.
* `TicketProvider.of(ticket)` makes the calls as the user a ticket belongs to (e.g. the end user of your application)
  instead of a single technical user.
* Errors are `StatusRuntimeException`s carrying the module's status (`NOT_FOUND`, `PERMISSION_DENIED`, ...).
* The CA certificate of the Docker stack is in the `grpc-certs` volume (`ca.crt`). The module listens with TLS on
  `0.0.0.0` in Docker: a plaintext channel (`AlfrescoGrpcChannels.plaintext`) works only against a module bound to
  loopback.

### Run the example

`examples/java-client` is a minimal Spring Boot application that logs in through `AuthenticationService` as the
`mcp` user, lists the children of Company Home (`-root-`) and the 10 documents modified most recently (search sorted
by `cm:modified`), each with its parent, then exits. Host, port, user and password are in its `application.yml`
(`ALFRESCO_HOST`, `ALFRESCO_PORT`, `ALFRESCO_USERNAME`, `ALFRESCO_PASSWORD`). With the Docker stack running, build only this module and the
modules it depends on (`-am`: the parent and `protoalf-client`), from the repository root:

```
mvn -pl examples/java-client -am package -DskipTests
```

Run it from the module folder, because `alfresco.trust-certificate` in its `application.yml` is the relative path
`ca.crt`:

```
cd examples/java-client
java -jar target/protoalf-example.jar
```

From another folder, pass the CA explicitly: `--alfresco.trust-certificate=<path>/ca.crt`.

`ca.crt` must be the CA of the running stack. `purge` regenerates it (a stale one fails with a TLS error): copy the
current one from the root of the repository with

```
docker compose -f docker/docker-compose.yml cp mcp:/grpc-certs/ca.crt examples/java-client/ca.crt
```

## Addressing nodes

Wherever a node is expected (gRPC `node_ref`, MCP tool `nodeRef`) you can pass:

| Value | Meaning |
|---|---|
| `workspace://SpacesStore/<id>` | A full nodeRef |
| `<id>` | A bare node id, `workspace://SpacesStore` is assumed |
| `-root-` | Company Home |
| `-my-` | Home folder of the calling user |
| `-shared-` | Shared Files |

Aliases are resolved as the calling user; the returned `Node.node_ref` always contains the real nodeRef.
`Node.parent_ref` is the nodeRef of the primary parent, empty for the repository root or when the calling user
cannot read the parent.

## Search

`SearchService.Query` runs an AFTS (default, `language: "afts"`) or CMIS SQL (`language: "cmis"`) query as the
calling user through Alfresco Search Services, so results are permission filtered and reflect the index, which can
lag a few seconds behind the repository. The MCP tool `alfresco_search` exposes AFTS only, e.g.
`cm:name:"budget*" AND TYPE:"cm:content"`, `TEXT:"invoice"`, `PATH:"/app:company_home/cm:Projects//*"`.
Malformed queries are rejected with `INVALID_ARGUMENT`.

Results are sorted by relevance unless `SearchRequest.sort` lists up to 10 `SortField`s, applied in order: `field` is
a property (`cm:modified`, `{uri}name`) or `score`, ascending unless `descending` is set. Sorting is for AFTS only
(a CMIS query uses `ORDER BY`); unknown properties are rejected with `INVALID_ARGUMENT`. The MCP tool takes it as
text, e.g. `sort: "cm:modified desc, cm:name"`.

## Content models

`DictionaryService.GetAllModels` (MCP tool `alfresco_list_models`) describes the data dictionary through the
`DictionaryService`: every deployed content model with its namespaces, types and aspects (name, title, parent,
mandatory aspects). Filter by namespace prefix (`cm`) or model name (`cm:contentmodel`); with `include_properties`
(which requires the filter) each type/aspect also lists the properties it defines, with data type and the
`mandatory`, `multiValued` and `readOnly` flags. Inherited properties are listed on the parent class. This is how a
model finds the right `TYPE:"..."` for a search or the property names to set with the write tools.

## Writing

| gRPC | MCP tool | What |
|---|---|---|
| `FileFolderService.Create` | `alfresco_create_folder`, `alfresco_create_document` | New folder, or document with text (`content`) or base64 (`contentBase64`) content |
| `NodeService.UpdateNode` | `alfresco_update_node` | Rename, change type and/or set metadata |
| `ContentService.GetWriter` | `alfresco_update_content` | Replace the content of a document |
| `FileFolderService.Move` / `FileFolderService.Copy` | `alfresco_move_node` / `alfresco_copy_node` | Move or (deep) copy into a folder, optionally with a new name |
| `NodeService.DeleteNode` | `alfresco_delete_node` | Move to the trashcan (restorable); Company Home, Shared Files and the user home are refused |

Metadata is passed as property name → text, e.g. `{"cm:title": "Budget", "cm:description": "Draft"}`, converted to the
property type (dates in ISO 8601). Protected (`cm:created`, ...) and `sys:` properties are rejected; aspects such as
`cm:titled` are added automatically. Alfresco enforces permissions, locks, unique names (`ALREADY_EXISTS`) and model
constraints (`FAILED_PRECONDITION`). Requests are limited to `protoalf.max-message-size` (16 MB): this limits
non-streaming requests, including their metadata. For larger documents, use `UploadContent` (see below).

`property_values` (FileFolderService.Create, NodeService.UpdateNode, the UploadContent header) takes a list of values per property, e.g.
`{"cm:taggable": ["finance", "2026"]}`: a multi-valued property gets the whole list (empty = unset), a single-valued one
accepts zero or one value. A property must not be in both `properties` and `property_values`.

`aspects` (FileFolderService.Create, UploadContent header) and `add_aspects` / `remove_aspects` (NodeService.UpdateNode) add and remove aspects
such as `cm:taggable`. Only known aspects outside the `sys:` namespace are accepted; an aspect already present (or
absent, for removal) is ignored. Aspects are added before the properties are set, so their properties can be written.

## Authentication and streaming

| gRPC | What |
|---|---|
| `AuthenticationService.Authenticate` | User and password → Alfresco ticket. The only RPC callable without the `alfresco-ticket` header. Wrong credentials are `UNAUTHENTICATED` |
| `DownloadContent` (server streaming) | Stored bytes of a document: one info message (mimetype, encoding, size), then data chunks of at most 1 MB |
| `UploadContent` (client streaming) | New document: the header first (parent, name, type, properties, mimetype, encoding), then data chunks of at most 1 MB. Limited to 2 GiB (`GrpcContentService.MAX_UPLOAD_BYTES`), larger uploads are `INVALID_ARGUMENT`. Not limited by `max-message-size`, since each message is small |

The client of `Authenticate` is `AuthenticationServiceClient`; `NodeServiceClient` dispatches the remaining RPCs to the corresponding repository services.

## Versions

`VersionService.GetVersionHistory` returns the version history of a document through the `VersionService`, newest first
(empty when the document is not versionable): label, major/minor, description, modifier, date and the frozen
`Node` with the metadata of that version. Its `version_ref` (`versionStore://version2Store/<id>`) is accepted by
`ContentService.GetReader` and `ContentService.DownloadContent` to read the content of that version.

## The MCP user

In Docker the MCP server works as the **non-admin** user `mcp`, created by the one-shot `mcp-user`
container (`docker/mcp-user.sh`). It can:

* write in its home folder (`-my-`, `/User Homes/mcp`) and in `/Company Home/MCP` (Coordinator);
* elsewhere, only what Alfresco grants every user (usually read). Grant more by adding `mcp` to folders or sites.

Its password is `example`, or the `MCP_ALFRESCO_PASSWORD` environment variable when starting the stack.

## Security notes (development setup)

* The gRPC module refuses plaintext on non-loopback addresses. In Docker it listens on `0.0.0.0` with TLS: the
  `certs` container generates a development CA and server certificate into the `grpc-certs`
  volume; the MCP server trusts that CA.
* The MCP server logs into Alfresco as a single user (`ALFRESCO_USERNAME` / `ALFRESCO_PASSWORD`): every tool call,
  read or write, runs with that user's permissions. Outside Docker the default is `admin`: set a dedicated user.
* The MCP endpoint itself has no authentication: anyone reaching port 8090 can write as that user.
  Do not expose it outside your machine.

## Configuration

Alfresco module (`alfresco-global.properties` or `-D` options):

| Property | Default |
|---|---|
| `protoalf.enabled` | `true` |
| `protoalf.host` / `protoalf.port` | `127.0.0.1` / `9090` |
| `protoalf.threads` | `8` |
| `protoalf.max-message-size` | `16777216` (bytes, maximum individual gRPC message size) |
| `protoalf.tls.certificate` / `protoalf.tls.private-key` | empty (plaintext, loopback only) |

MCP server (`application.yml` or environment variables):

| Property | Env | Default |
|---|---|---|
| `alfresco.username` / `alfresco.password` | `ALFRESCO_USERNAME` / `ALFRESCO_PASSWORD` | `admin` / `admin` |
| `alfresco.grpc.host` / `alfresco.grpc.port` | `ALFRESCO_GRPC_HOST` / `ALFRESCO_GRPC_PORT` | `localhost` / `9090` |
| `alfresco.grpc.trust-certificate` | `ALFRESCO_GRPC_TRUST_CERTIFICATE` | empty (plaintext) |
| `alfresco.grpc.deadline` | `ALFRESCO_GRPC_DEADLINE` | `30s` |
