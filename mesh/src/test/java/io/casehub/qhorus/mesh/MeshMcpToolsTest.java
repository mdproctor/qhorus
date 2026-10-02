package io.casehub.qhorus.mesh;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MeshMcpToolsTest {

    @Inject MeshMcpTools tools;

    @Test
    @Transactional
    void register_and_discover() {
        String reg1 = tools.mesh_register("casehub/qhorus", "qhorus session",
                "{\"project\":\"qhorus\",\"family\":\"casehub\"}");
        assertThat(reg1).contains("casehub/qhorus");

        String reg2 = tools.mesh_register("casehub/claudony", "claudony session",
                "{\"project\":\"claudony\",\"family\":\"casehub\"}");
        assertThat(reg2).contains("casehub/claudony");

        String peers = tools.mesh_discover_peers("family", "casehub");
        assertThat(peers).contains("casehub/qhorus");
        assertThat(peers).contains("casehub/claudony");

        String qhorusPeers = tools.mesh_discover_peers("project", "qhorus");
        assertThat(qhorusPeers).contains("casehub/qhorus");
        assertThat(qhorusPeers).doesNotContain("casehub/claudony");
    }

    @Test
    @Transactional
    void create_channel_and_send_message() {
        tools.mesh_register("test-sender", "test", "{}");

        String created = tools.mesh_create_channel("design-review",
                "{\"project\":\"qhorus\",\"purpose\":\"review\"}");
        assertThat(created).contains("design-review");

        String sent = tools.mesh_send_message("design-review", "test-sender",
                "status", "Has anyone reviewed the mesh spec?");
        assertThat(sent).contains("STATUS");

        String messages = tools.mesh_check_messages("design-review", null);
        assertThat(messages).contains("Has anyone reviewed the mesh spec?");
    }

    @Test
    @Transactional
    void list_channels_filtered_by_metadata() {
        tools.mesh_create_channel("qhorus-work",
                "{\"project\":\"qhorus\"}");
        tools.mesh_create_channel("claudony-work",
                "{\"project\":\"claudony\"}");

        String filtered = tools.mesh_list_channels("project", "qhorus");
        assertThat(filtered).contains("qhorus-work");
        assertThat(filtered).doesNotContain("claudony-work");
    }
}
