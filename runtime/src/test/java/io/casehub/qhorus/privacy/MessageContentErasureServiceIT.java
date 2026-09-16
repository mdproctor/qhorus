package io.casehub.qhorus.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ErasureReason;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.privacy.MessageContentErasureEntry;
import io.casehub.qhorus.runtime.privacy.MessageContentErasureService;
import io.casehub.qhorus.runtime.privacy.MessageContentErasureService.MessageErasureResult;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class MessageContentErasureServiceIT {

    @Inject MessageContentErasureService erasureService;
    @Inject QhorusMcpTools tools;
    @Inject ChannelService channelService;
    @Inject MessageLedgerEntryRepository ledgerRepo;
    @Inject @PersistenceUnit("qhorus") EntityManager em;

    @Test
    void eraseMessageContent_happyPath_contentNulledAndTombstoneWritten() {
        String chName = "erasure-hp-" + UUID.randomUUID().toString().substring(0, 8);
        tools.createChannel(chName, "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.registerInstance(chName, "agent-a", null, null, null);
        tools.sendMessage(chName, "agent-a", "status", "sensitive content to erase",
                null, null, null, null, null, null, null, null, null);

        UUID channelId = channelService.findByName(chName).map(Channel::id).orElseThrow();
        var entries = ledgerRepo.findByChannelId(channelId, null);
        MessageLedgerEntry ledgerEntry = entries.stream()
                .filter(e -> "sensitive content to erase".equals(e.content))
                .findFirst().orElseThrow();
        String originalDigest = ledgerEntry.digest;

        MessageErasureResult result = erasureService.eraseMessageContent(
                ledgerEntry.id, ErasureReason.GDPR_ART_17_REQUEST);

        assertThat(result.erasedEntryId()).isEqualTo(ledgerEntry.id);
        assertThat(result.erasedMessageId()).isEqualTo(ledgerEntry.messageId);
        assertThat(result.channelId()).isEqualTo(channelId);
        assertThat(result.tombstoneEntryId()).isNotNull();

        em.flush();
        em.clear();

        MessageLedgerEntry reloaded = em.find(MessageLedgerEntry.class, ledgerEntry.id);
        assertThat(reloaded.content).isNull();
        assertThat(reloaded.agentSignature).isNull();
        assertThat(reloaded.agentPublicKey).isNull();
        assertThat(reloaded.agentKeyRef).isNull();

        MessageContentErasureEntry tombstone = em.find(MessageContentErasureEntry.class, result.tombstoneEntryId());
        assertThat(tombstone).isNotNull();
        assertThat(tombstone.erasedEntryId).isEqualTo(ledgerEntry.id);
        assertThat(tombstone.originalDigest).isEqualTo(originalDigest);
        assertThat(tombstone.erasureReason).isEqualTo(ErasureReason.GDPR_ART_17_REQUEST);

        MessageEntity messageEntity = em.find(MessageEntity.class, ledgerEntry.messageId);
        assertThat(messageEntity.content).isNull();
    }

    @Test
    void eraseMessageContent_alreadyErased_idempotent() {
        String chName = "erasure-idem-" + UUID.randomUUID().toString().substring(0, 8);
        tools.createChannel(chName, "APPEND", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        tools.registerInstance(chName, "agent-b", null, null, null);
        tools.sendMessage(chName, "agent-b", "status", "erase me twice",
                null, null, null, null, null, null, null, null, null);

        UUID channelId = channelService.findByName(chName).map(Channel::id).orElseThrow();
        var entries = ledgerRepo.findByChannelId(channelId, null);
        MessageLedgerEntry entry = entries.stream()
                .filter(e -> "erase me twice".equals(e.content))
                .findFirst().orElseThrow();

        MessageErasureResult first = erasureService.eraseMessageContent(
                entry.id, ErasureReason.GDPR_ART_17_REQUEST);
        assertThat(first.tombstoneEntryId()).isNotNull();

        em.flush();
        em.clear();

        MessageErasureResult second = erasureService.eraseMessageContent(
                entry.id, ErasureReason.GDPR_ART_17_REQUEST);
        assertThat(second.tombstoneEntryId()).isEqualTo(first.tombstoneEntryId());
    }

    @Test
    void eraseMessageContent_nonExistentEntry_throwsIAE() {
        assertThrows(IllegalArgumentException.class,
                () -> erasureService.eraseMessageContent(
                        UUID.randomUUID(), ErasureReason.GDPR_ART_17_REQUEST));
    }
}
