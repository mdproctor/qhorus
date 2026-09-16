package io.casehub.qhorus.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.ErasureReason;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.runtime.privacy.MessageContentErasureEntry;

class MessageContentErasureEntryTest {

    @Test
    void canonicalBytes_includesDomainFields() {
        var entry = new MessageContentErasureEntry();
        entry.subjectId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        entry.entryType = LedgerEntryType.EVENT;
        entry.actorId = "system:message-erasure";
        entry.actorType = ActorType.SYSTEM;
        entry.actorRole = "MessageContentErasureService";
        entry.erasedEntryId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        entry.erasedMessageId = 42L;
        entry.channelId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        entry.originalDigest = "abc123";
        entry.erasureReason = ErasureReason.GDPR_ART_17_REQUEST;

        String canonical = new String(entry.canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(canonical).contains("00000000-0000-0000-0000-000000000001");
        assertThat(canonical).contains("42");
        assertThat(canonical).contains("00000000-0000-0000-0000-000000000002");
        assertThat(canonical).contains("abc123");
        assertThat(canonical).contains("GDPR_ART_17_REQUEST");
    }

    @Test
    void canonicalBytes_nullDomainFieldsRenderAsEmpty() {
        var entry = new MessageContentErasureEntry();
        entry.entryType = LedgerEntryType.EVENT;
        String canonical = new String(entry.canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(canonical).contains("||||");
    }
}
