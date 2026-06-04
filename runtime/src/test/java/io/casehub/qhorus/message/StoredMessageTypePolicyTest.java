package io.casehub.qhorus.message;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.message.StoredMessageTypePolicy;

class StoredMessageTypePolicyTest {

    private final StoredMessageTypePolicy policy = new StoredMessageTypePolicy();

    @Test
    void nullAllowedTypes_permitsAllTypes() {
        Channel ch = channel(null);
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.QUERY));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.EVENT));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.COMMAND));
    }

    @Test
    void blankAllowedTypes_permitsAllTypes() {
        Channel ch = channel("   ");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.STATUS));
    }

    @Test
    void singleType_permitsThatType() {
        Channel ch = channel("EVENT");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void singleType_rejectsOtherType() {
        Channel ch = channel("EVENT");
        assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.QUERY));
    }

    @Test
    void multipleTypes_permitsAllListed() {
        Channel ch = channel("QUERY,COMMAND,RESPONSE");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.QUERY));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.COMMAND));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.RESPONSE));
    }

    @Test
    void multipleTypes_rejectsUnlisted() {
        Channel ch = channel("QUERY,COMMAND,RESPONSE");
        assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void whitespaceAroundCommas_isTrimmed() {
        Channel ch = channel("EVENT , STATUS");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.EVENT));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.STATUS));
    }

    @Test
    void unknownTypeName_throwsIllegalArgument() {
        Channel ch = channel("RUBBISH");
        assertThrows(IllegalArgumentException.class,
                () -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void violationMessage_containsChannelNameAndTypes() {
        Channel ch = channel("EVENT");
        ch.name = "case-abc/observe";
        MessageTypeViolationException ex = assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.QUERY));
        assertTrue(ex.getMessage().contains("case-abc/observe"));
        assertTrue(ex.getMessage().contains("QUERY"));
        assertTrue(ex.getMessage().contains("EVENT"));
    }

    @Test
    void allNineTypes_permitted_whenOpen() {
        Channel ch = channel(null);
        for (MessageType t : MessageType.values()) {
            assertDoesNotThrow(() -> policy.validate(ch, t),
                    "Expected " + t + " to be permitted on open channel");
        }
    }

    // ------------------------------------------------------------------
    // Denied types — denial-first semantics
    // ------------------------------------------------------------------

    @Test
    void deniedType_onOpenChannel_isRejected() {
        Channel ch = channelWithDenied(null, "EVENT");
        assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void deniedType_onOpenChannel_otherTypesStillPass() {
        Channel ch = channelWithDenied(null, "EVENT");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.QUERY));
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.COMMAND));
    }

    @Test
    void deniedType_exceptionMessageIndicatesDenial() {
        Channel ch = channelWithDenied(null, "EVENT");
        ch.name = "case-abc/oversight";
        MessageTypeViolationException ex = assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.EVENT));
        assertTrue(ex.getMessage().contains("denies"), "Expected 'denies' in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("case-abc/oversight"));
        assertTrue(ex.getMessage().contains("EVENT"));
    }

    @Test
    void nullDeniedTypes_hasNoEffect() {
        Channel ch = channelWithDenied("QUERY", null);
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.QUERY));
        assertThrows(MessageTypeViolationException.class,
                () -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void blankDeniedTypes_hasNoEffect() {
        Channel ch = channelWithDenied(null, "  ");
        assertDoesNotThrow(() -> policy.validate(ch, MessageType.EVENT));
    }

    @Test
    void allNineTypes_permitted_whenOpenAndNoDenied() {
        Channel ch = channelWithDenied(null, null);
        for (MessageType t : MessageType.values()) {
            assertDoesNotThrow(() -> policy.validate(ch, t),
                    "Expected " + t + " to be permitted on fully open channel");
        }
    }

    private Channel channel(String allowedTypes) {
        return channelWithDenied(allowedTypes, null);
    }

    private Channel channelWithDenied(String allowedTypes, String deniedTypes) {
        Channel ch = new Channel();
        ch.name = "test-channel";
        ch.allowedTypes = allowedTypes;
        ch.deniedTypes = deniedTypes;
        ch.semantic = ChannelSemantic.APPEND;
        return ch;
    }
}
