package io.casehub.qhorus.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class MessageContentErasureMcpIT {

    @Inject QhorusMcpTools tools;
    @Inject ChannelService channelService;
    @Inject MessageLedgerEntryRepository ledgerRepo;

    @Test
    void eraseMessageContent_viaMcpTool_returnsConfirmation() {
        String chName = "mcp-erasure-" + UUID.randomUUID().toString().substring(0, 8);
        tools.createChannel(chName, "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.registerInstance(chName, "agent-mcp", null, null, null);
        tools.sendMessage(chName, "agent-mcp", "status", "mcp erasure target",
                null, null, null, null, null, null, null, null, null);

        UUID channelId = channelService.findByName(chName).map(Channel::id).orElseThrow();
        var entries = ledgerRepo.findByChannelId(channelId, null);
        MessageLedgerEntry entry = entries.stream()
                .filter(e -> "mcp erasure target".equals(e.content))
                .findFirst().orElseThrow();

        String response = tools.eraseMessageContent(entry.id.toString(), "GDPR_ART_17_REQUEST");

        assertThat(response).contains("erased");
        assertThat(response).contains(entry.id.toString());
    }
}
