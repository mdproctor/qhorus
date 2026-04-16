package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools.ArtefactDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class SharedDataToolTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    InstanceService instanceService;

    @Test
    @TestTransaction
    void shareDataCreatesCompleteArtefact() {
        ArtefactDetail result = tools.shareData("my-report", "Analysis report",
                "alice", "Full content here", false, true);

        assertNotNull(result.artefactId());
        assertEquals("my-report", result.key());
        assertTrue(result.complete());
        assertEquals("Full content here".length(), result.sizeBytes());
    }

    @Test
    @TestTransaction
    void shareDataChunkedUpload() {
        tools.shareData("chunked-report", "Big report", "alice", "chunk1", false, false);
        tools.shareData("chunked-report", null, "alice", " chunk2", true, false);
        ArtefactDetail final_ = tools.shareData("chunked-report", null, "alice", " chunk3", true, true);

        assertEquals("chunk1 chunk2 chunk3", final_.content());
        assertTrue(final_.complete());
    }

    @Test
    @TestTransaction
    void getSharedDataByKey() {
        tools.shareData("get-by-key", "desc", "alice", "content", false, true);

        ArtefactDetail found = tools.getSharedData("get-by-key", null);

        assertEquals("get-by-key", found.key());
        assertEquals("content", found.content());
    }

    @Test
    @TestTransaction
    void getSharedDataByUuid() {
        ArtefactDetail stored = tools.shareData("get-by-uuid", "desc", "alice", "content", false, true);

        ArtefactDetail found = tools.getSharedData(null, stored.artefactId().toString());

        assertEquals(stored.artefactId(), found.artefactId());
    }

    @Test
    @TestTransaction
    void getSharedDataMissingKeyThrows() {
        assertThrows(ToolCallException.class, () -> tools.getSharedData("no-such-key", null));
    }

    @Test
    @TestTransaction
    void getSharedDataBothNullThrowsIllegalArgument() {
        assertThrows(ToolCallException.class, () -> tools.getSharedData(null, null),
                "providing neither key nor id should throw IllegalArgumentException");
    }

    @Test
    @TestTransaction
    void getSharedDataMalformedUuidThrows() {
        assertThrows(ToolCallException.class, () -> tools.getSharedData(null, "not-a-uuid"));
    }

    @Test
    @TestTransaction
    void getSharedDataIncompleteReturnsResult() {
        ArtefactDetail partial = tools.shareData("partial-data", "desc", "alice", "chunk1", false, false);

        // Should return the artefact even if incomplete — content is available
        ArtefactDetail found = tools.getSharedData("partial-data", null);
        assertFalse(found.complete());
    }

    @Test
    @TestTransaction
    void listSharedDataIncludesStoredArtefacts() {
        tools.shareData("list-a", "desc", "alice", "content a", false, true);
        tools.shareData("list-b", "desc", "bob", "content b", false, true);

        List<ArtefactDetail> all = tools.listSharedData();

        assertTrue(all.stream().anyMatch(d -> "list-a".equals(d.key())));
        assertTrue(all.stream().anyMatch(d -> "list-b".equals(d.key())));
    }

    @Test
    @TestTransaction
    void listSharedDataIncludesIncompleteArtefacts() {
        tools.shareData("incomplete-item", "desc", "alice", "chunk1", false, false);

        List<ArtefactDetail> all = tools.listSharedData();

        assertTrue(all.stream().anyMatch(d -> "incomplete-item".equals(d.key())));
    }

    @Test
    @TestTransaction
    void claimAndReleaseChangesGcEligibility() {
        ArtefactDetail artefact = tools.shareData("claim-test", "desc", "alice", "content", false, true);
        var claimant = instanceService.register("claim-agent", "Agent", List.of());

        tools.claimArtefact(artefact.artefactId().toString(), claimant.id.toString());
        assertFalse(tools.isGcEligible(artefact.artefactId().toString()),
                "claimed artefact should not be GC eligible");

        tools.releaseArtefact(artefact.artefactId().toString(), claimant.id.toString());
        assertTrue(tools.isGcEligible(artefact.artefactId().toString()),
                "released artefact with no remaining claims should be GC eligible");
    }
}
