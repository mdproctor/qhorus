package io.casehub.qhorus.runtime.privacy;

import io.casehub.ledger.api.model.ErasureReason;
import io.casehub.ledger.runtime.model.jpa.JpaLedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@NamedQuery(
    name = "MessageContentErasureEntry.findByErasedEntryId",
    query = "SELECT e FROM MessageContentErasureEntry e " +
            "WHERE e.erasedEntryId = :erasedEntryId AND e.tenancyId = :tenancyId")
@Entity
@Table(name = "message_content_erasure_entry")
@DiscriminatorValue("QHORUS_MESSAGE_CONTENT_ERASURE")
public class MessageContentErasureEntry extends JpaLedgerEntry {

    @Column(name = "erased_entry_id", nullable = false)
    public UUID erasedEntryId;

    @Column(name = "erased_message_id", nullable = false)
    public Long erasedMessageId;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "original_digest", nullable = false)
    public String originalDigest;

    @Enumerated(EnumType.STRING)
    @Column(name = "erasure_reason", nullable = false)
    public ErasureReason erasureReason;

    @Override
    protected byte[] domainContentBytes() {
        String canonical = String.join("|",
            erasedEntryId != null ? erasedEntryId.toString() : "",
            erasedMessageId != null ? erasedMessageId.toString() : "",
            channelId != null ? channelId.toString() : "",
            originalDigest != null ? originalDigest : "",
            erasureReason != null ? erasureReason.name() : ""
        );
        return canonical.getBytes(StandardCharsets.UTF_8);
    }
}
