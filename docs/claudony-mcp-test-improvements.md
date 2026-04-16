# Claudony MCP Test Quality Improvements

Follow-up to the `McpServer.java` → `quarkus-mcp-server` migration (commit `6faf297`).
The migration unblocked Qhorus Phase 8 but introduced test quality regressions.
This document describes what to fix and why.

---

## What regressed and why

The migration moved tool logic from a hand-rolled dispatcher into `@Tool` methods on
`ClaudonyMcpTools`, which is correct. But the tests were updated minimally — the
existing HTTP-level tests were patched with Accept headers and session ID handling
rather than rethought. Two concerns that should be separate ended up mixed together:

| Concern | What it tests | Right test type |
|---|---|---|
| **Tool logic** | Does `list_sessions` format output correctly? Does `create_session` pass optional `command` as null when omitted? | Direct CDI method call — no HTTP |
| **Protocol compliance** | Does the endpoint reject wrong Accept headers? Does it enforce `initialize` before tool calls? | HTTP test — `@QuarkusTest` against `/mcp` |

Currently both concerns live in `McpServerTest` and both pay the cost of the full
Streamable HTTP protocol per test (Accept header, `Mcp-Session-Id` handshake, JSON-RPC
wrapping). Tool logic tests shouldn't know the protocol exists.

---

## Fix 1 — Split `McpServerTest` into two focused test classes

### `ClaudonyMcpToolsTest` (new) — tool logic only

`@QuarkusTest` + `@Inject ClaudonyMcpTools tools`. Calls methods directly. No HTTP,
no JSON-RPC, no session IDs. Mocks `ServerClient` and `TerminalAdapterFactory` as before.

```java
@QuarkusTest
class ClaudonyMcpToolsTest {

    @Inject ClaudonyMcpTools tools;

    @InjectMock @RestClient ServerClient serverClient;
    @InjectMock TerminalAdapterFactory terminalFactory;

    @BeforeEach
    void reset() {
        Mockito.reset(serverClient, terminalFactory);
        Mockito.when(terminalFactory.resolve()).thenReturn(Optional.empty());
    }

    @Test
    void listSessions_empty_returnsNoActiveMessage() {
        Mockito.when(serverClient.listSessions()).thenReturn(List.of());
        assertThat(tools.listSessions()).isEqualTo("No active sessions.");
    }

    @Test
    void listSessions_withSessions_formatsEachSession() {
        var now = Instant.now();
        Mockito.when(serverClient.listSessions()).thenReturn(List.of(
            new SessionResponse("id-1", "claudony-proj", "/tmp", "claude",
                SessionStatus.IDLE, now, now, "ws://...", "http://...", null, null, null)));
        assertThat(tools.listSessions())
            .contains("claudony-proj")
            .contains("id-1")
            .contains("IDLE");
    }

    @Test
    void createSession_nullCommand_passesNullToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-2", "claudony-new", "/home", "claude",
                SessionStatus.IDLE, now, now, "ws://...", "http://.../id-2", null, null, null));

        tools.createSession("new", "/home", null);

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> r.command() == null));
    }

    @Test
    void createSession_blankCommand_treatedAsNull() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-3", "n", "/", "claude",
                SessionStatus.IDLE, now, now, "ws://...", "http://.../id-3", null, null, null));

        tools.createSession("n", "/", "   ");

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> r.command() == null));
    }

    @Test
    void createSession_withCommand_passesCommandToServer() {
        var now = Instant.now();
        Mockito.when(serverClient.createSession(Mockito.any()))
            .thenReturn(new SessionResponse("id-4", "n", "/", "claude",
                SessionStatus.IDLE, now, now, "ws://...", "http://.../id-4", null, null, null));

        tools.createSession("n", "/", "bash");

        Mockito.verify(serverClient).createSession(
            Mockito.argThat(r -> "bash".equals(r.command())));
    }

    @Test
    void deleteSession_delegatesToServer() {
        tools.deleteSession("id-1");
        Mockito.verify(serverClient).deleteSession("id-1");
        assertThat(tools.deleteSession("id-1")).isEqualTo("Session deleted.");
    }

    @Test
    void renameSession_returnsFormattedName() {
        var now = Instant.now();
        Mockito.when(serverClient.renameSession("id-1", "newname"))
            .thenReturn(new SessionResponse("id-1", "claudony-newname", "/tmp", "claude",
                SessionStatus.IDLE, now, now, "ws://...", "http://...", null, null, null));

        assertThat(tools.renameSession("id-1", "newname"))
            .contains("claudony-newname");
    }

    @Test
    void sendInput_passesTextLiterally() {
        tools.sendInput("id-1", "echo Escape marker\n");
        Mockito.verify(serverClient).sendInput(
            Mockito.eq("id-1"),
            Mockito.argThat(r -> "echo Escape marker\n".equals(r.text())));
    }

    @Test
    void getOutput_nullLines_defaultsTo50() {
        Mockito.when(serverClient.getOutput("id-1", 50)).thenReturn("output");
        tools.getOutput("id-1", null);
        Mockito.verify(serverClient).getOutput("id-1", 50);
    }

    @Test
    void getOutput_explicitLines_usesProvided() {
        Mockito.when(serverClient.getOutput("id-1", 20)).thenReturn("output");
        tools.getOutput("id-1", 20);
        Mockito.verify(serverClient).getOutput("id-1", 20);
    }

    @Test
    void openInTerminal_noAdapter_returnsHelpfulMessage() {
        // terminalFactory.resolve() already returns empty from @BeforeEach
        assertThat(tools.openInTerminal("id-1"))
            .isEqualTo("No terminal adapter available on this machine.");
    }

    @Test
    void getServerInfo_containsExpectedFields() {
        assertThat(tools.getServerInfo())
            .contains("Server URL:")
            .contains("Agent mode:")
            .contains("Terminal adapter: none");
    }
}
```

Delete `McpServerTest` once `ClaudonyMcpToolsTest` and `McpProtocolTest` (below) are in place.

---

### `McpProtocolTest` (new) — protocol compliance only

Tests the Streamable HTTP endpoint behaviour. Small — 4–5 tests maximum.

```java
@QuarkusTest
class McpProtocolTest {

    // Helper — shared across all tests in this class
    private io.restassured.specification.RequestSpecification mcp(String sessionId) {
        var spec = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream");
        if (sessionId != null) spec.header("Mcp-Session-Id", sessionId);
        return spec;
    }

    private String initialize() {
        return given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().header("Mcp-Session-Id");
    }

    @Test
    void post_withoutCorrectAcceptHeader_isRejected() {
        given().contentType(ContentType.JSON)
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
            .when().post("/mcp")
            .then().statusCode(not(equalTo(200)));
    }

    @Test
    void initialize_returnsMcpSessionIdHeader() {
        assertThat(initialize()).isNotNull();
    }

    @Test
    void toolsList_withoutSessionId_isRejected() {
        mcp(null)
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("error.code", equalTo(-32600)); // verify exact code from library
    }

    @Test
    void unknownMethod_returns32601() {
        var sid = initialize();
        mcp(sid)
            .body("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nonexistent\",\"params\":{}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("error.code", equalTo(-32601));
    }

    @Test
    void toolsList_withValidSession_returnsAll8ClaudonyTools() {
        var sid = initialize();
        mcp(sid)
            .body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")
            .when().post("/mcp")
            .then()
            .statusCode(200)
            .body("result.tools.name", hasItems(
                "list_sessions", "create_session", "delete_session",
                "rename_session", "send_input", "get_output",
                "open_in_terminal", "get_server_info"));
    }
}
```

> **Note:** The error code for `tools/list` without a session ID (line 3 of the tests above)
> needs to be verified by running the test first — check what quarkus-mcp-server actually
> returns and pin the exact value. Do not leave `not(equalTo(200))` as the assertion.

---

## Fix 2 — Make `McpServerIntegrationTest` independent

**Current problem:** 12 ordered tests share two pieces of static state (`mcpSessionId` and
`createdSessionId`). If test 1 fails, all 12 fail together.

**Fix:** Give each test a `@BeforeEach`/`@AfterEach` pair and collapse the 4-test
create→input→output→delete sequence into a single self-contained test.

```java
@QuarkusTest
@TestSecurity(user = "test", roles = "user")
class McpServerIntegrationTest {

    private String sessionId;   // MCP protocol session, fresh per test
    private String tmuxSession; // tmux session for tests that need one

    @BeforeEach
    void initMcpSession() {
        var response = given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .body("""
                {"jsonrpc":"2.0","id":0,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{},
                           "clientInfo":{"name":"test","version":"1"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200).extract().response();
        sessionId = response.header("Mcp-Session-Id");
    }

    @AfterEach
    void cleanupTmuxSession() {
        if (tmuxSession != null) {
            mcp().body(deleteBody(tmuxSession)).when().post("/mcp");
            tmuxSession = null;
        }
    }

    private io.restassured.specification.RequestSpecification mcp() {
        return given()
            .contentType(ContentType.JSON)
            .accept("application/json, text/event-stream")
            .header("Mcp-Session-Id", sessionId);
    }

    // --- Tests ---

    @Test
    void fullSessionLifecycle_createSendReceiveDelete() throws Exception {
        // Create
        var text = mcp()
            .body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"create_session",
                           "arguments":{"name":"mcp-lifecycle","workingDir":"/tmp",
                                        "command":"echo mcp-ok"}}}
                """)
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("claudony-mcp-lifecycle"))
            .extract().<String>path("result.content[0].text");

        tmuxSession = extractSessionId(text);
        Assumptions.assumeTrue(tmuxSession != null, "Could not extract session ID");

        // Send input
        mcp().body(sendInputBody(tmuxSession, "echo mcp-input-marker\n"))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Input sent."));

        Thread.sleep(300);

        // Get output
        mcp().body(getOutputBody(tmuxSession, 20))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("mcp-input-marker"));

        // Delete (also done by @AfterEach but explicit here for test clarity)
        mcp().body(deleteBody(tmuxSession))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Session deleted."));
        tmuxSession = null;
    }

    @Test
    void renameSession_updatesNameThroughFullChain() {
        var text = mcp()
            .body(createBody("mcp-rename-test", "/tmp", "bash"))
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().<String>path("result.content[0].text");

        tmuxSession = extractSessionId(text);
        Assumptions.assumeTrue(tmuxSession != null);

        mcp().body("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"rename_session",
                           "arguments":{"id":"%s","name":"mcp-renamed"}}}
                """.formatted(tmuxSession))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("claudony-mcp-renamed"));
    }

    @Test
    void inputWithTmuxKeyName_appearsAsLiteralText() throws Exception {
        var text = mcp()
            .body(createBody("mcp-keyname-test", "/tmp", "bash"))
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().<String>path("result.content[0].text");

        tmuxSession = extractSessionId(text);
        Assumptions.assumeTrue(tmuxSession != null);
        Thread.sleep(300);

        mcp().body(sendInputBody(tmuxSession, "Escape"))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", equalTo("Input sent."));

        Thread.sleep(300);

        mcp().body(getOutputBody(tmuxSession, 20))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("Escape"));
    }

    @Test
    void getServerInfo_returnsExpectedFields() {
        mcp().body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," +
                   "\"params\":{\"name\":\"get_server_info\",\"arguments\":{}}}")
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result.content[0].text", containsString("Server URL:"))
            .body("result.content[0].text", containsString("Agent mode:"));
    }

    // --- Helpers ---

    private String extractSessionId(String text) {
        var parts = text.split("Browser: http://localhost:\\d+/app/session/");
        return parts.length > 1 ? parts[1].trim() : null;
    }

    private String createBody(String name, String dir, String cmd) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"create_session",
                       "arguments":{"name":"%s","workingDir":"%s","command":"%s"}}}
            """.formatted(name, dir, cmd);
    }

    private String sendInputBody(String id, String text) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"send_input","arguments":{"id":"%s","text":"%s"}}}
            """.formatted(id, text);
    }

    private String getOutputBody(String id, int lines) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"get_output","arguments":{"id":"%s","lines":%d}}}
            """.formatted(id, lines);
    }

    private String deleteBody(String id) {
        return """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"delete_session","arguments":{"id":"%s"}}}
            """.formatted(id);
    }
}
```

---

## Fix 3 — Production code: narrow exception catch in `openInTerminal`

In `ClaudonyMcpTools.java`, line ~107:

```java
// Before — catches everything including RuntimeException, NPE, etc.
} catch (final Exception e) {
    return "Failed to open terminal: " + e.getMessage();
}

// After — catches only what TerminalAdapter.openSession() actually declares
} catch (final IOException | InterruptedException e) {
    return "Failed to open terminal: " + e.getMessage();
}
```

---

## Summary of changes

| File | Action | Reason |
|---|---|---|
| `ClaudonyMcpToolsTest.java` | Create new | Tests tool logic directly, no HTTP |
| `McpProtocolTest.java` | Create new | Tests protocol compliance with exact codes |
| `McpServerTest.java` | Delete | Superseded by the two above |
| `McpServerIntegrationTest.java` | Rework | Independent tests, no shared static state |
| `ClaudonyMcpTools.java` | Fix exception catch | Narrow to declared exceptions only |

Net result: fewer test lines, stronger assertions, zero shared static state, and tool
logic tests that run without protocol overhead.
