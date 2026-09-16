package io.casehub.qhorus.runtime.privacy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import io.casehub.ledger.api.model.ErasureReason;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.runtime.config.QhorusConfig;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.quarkus.hibernate.orm.PersistenceUnit;

@ApplicationScoped
public class MessageContentErasureService {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Inject
    LedgerEntryRepository ledger;

    @Inject
    QhorusConfig config;

    public record MessageErasureResult(
            UUID erasedEntryId,
            Long erasedMessageId,
            UUID channelId,
            UUID tombstoneEntryId) {
    }

    @Transactional
    public MessageErasureResult eraseMessageContent(
            final UUID ledgerEntryId,
            final ErasureReason reason) {

        if (!config.erasure().messageContent().enabled()) {
            throw new UnsupportedOperationException(
                    "Message content erasure is disabled");
        }

        final MessageLedgerEntry entry = em.find(MessageLedgerEntry.class, ledgerEntryId);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "No MessageLedgerEntry found for id: " + ledgerEntryId);
        }

        if (entry.content == null) {
            final var existing = em.createNamedQuery(
                    "MessageContentErasureEntry.findByErasedEntryId",
                    MessageContentErasureEntry.class)
                    .setParameter("erasedEntryId", ledgerEntryId)
                    .setParameter("tenancyId", entry.tenancyId)
                    .getResultList();
            final UUID tombstoneId = existing.isEmpty() ? null : existing.get(0).id;
            return new MessageErasureResult(
                    entry.id, entry.messageId, entry.channelId, tombstoneId);
        }

        final MessageContentErasureEntry tombstone = new MessageContentErasureEntry();
        tombstone.subjectId = entry.channelId;
        tombstone.entryType = LedgerEntryType.EVENT;
        tombstone.actorId = "system:message-erasure";
        tombstone.actorType = ActorType.SYSTEM;
        tombstone.actorRole = "MessageContentErasureService";
        tombstone.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        tombstone.erasedEntryId = entry.id;
        tombstone.erasedMessageId = entry.messageId;
        tombstone.channelId = entry.channelId;
        tombstone.originalDigest = entry.digest != null ? entry.digest : "";
        tombstone.erasureReason = reason;

        ledger.save(tombstone, TenancyConstants.DEFAULT_TENANT_ID);

        entry.content = null;
        entry.agentSignature = null;
        entry.agentPublicKey = null;
        entry.agentKeyRef = null;
        em.merge(entry);

        MessageEntity messageEntity = em.find(MessageEntity.class, entry.messageId);
        if (messageEntity != null) {
            messageEntity.content = null;
            em.merge(messageEntity);
        }

        return new MessageErasureResult(
                entry.id, entry.messageId, entry.channelId, tombstone.id);
    }
}
