package io.casehub.qhorus.runtime.channel;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ChannelSlugValidatorTest {

    // ── validateSlugPath: valid inputs ──

    @Test void acceptsSimpleSlug() {
        assertThatNoException().isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing-output"));
    }

    @Test void acceptsSingleSegment() {
        assertThatNoException().isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing"));
    }

    @Test void acceptsHierarchicalPath() {
        assertThatNoException().isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("case-abc/work"));
    }

    @Test void acceptsThreeSegmentPath() {
        assertThatNoException().isThrownBy(() ->
            io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("connector/twilio-sms-inbound/id-14155552671-3fa2b100"));
    }

    @Test void acceptsDigitsInSegment() {
        assertThatNoException().isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("case-abc-123/work2"));
    }

    // ── validateSlugPath: invalid inputs ──

    @Test void rejectsNull() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath(null))
            .withMessageContaining("null or blank");
    }

    @Test void rejectsBlank() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("  "))
            .withMessageContaining("null or blank");
    }

    @Test void rejectsUppercase() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("Billing-Output"))
            .withMessageContaining("Billing-Output");
    }

    @Test void rejectsSpace() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing output"))
            .withMessageContaining("billing output");
    }

    @Test void rejectsTrailingHyphen() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing-"))
            .withMessageContaining("billing-");
    }

    @Test void rejectsLeadingHyphen() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("-billing"))
            .withMessageContaining("-billing");
    }

    @Test void rejectsConsecutiveHyphens() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing--output"))
            .withMessageContaining("billing--output");
    }

    @Test void rejectsSegmentStartingWithDigit() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("123-billing"))
            .withMessageContaining("123-billing");
    }

    @Test void rejectsUuidShapedName_startingWithLetter() {
        // ~37% of UUIDs start with a-f; this must not be a valid channel name
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("a81b4c6d-1234-5678-abcd-ef0123456789"))
            .withMessageContaining("UUID-shaped");
    }

    @Test void rejectsTotalLengthOver200() {
        String longName = "a" + "b".repeat(200); // 201 chars
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath(longName))
            .withMessageContaining("200");
    }

    @Test void rejectsSegmentOver80Chars() {
        String longSegment = "a" + "b".repeat(80); // 81-char segment
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath(longSegment))
            .withMessageContaining("exceeds")
            .withMessageContaining("80");
    }

    @Test void rejectsLeadingSlash() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("/billing"))
            .withMessageContaining("/billing");
    }

    @Test void rejectsTrailingSlash() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("billing/"))
            .withMessageContaining("billing/");
    }

    @Test void rejectsUuidShapedSegmentInPath() {
        // Full path containing UUID-shaped segment — the UUID-shaped check is on the full name only.
        // A UUID like "a81b4c..." matches the segment pattern so is allowed as a path segment.
        // Only the FULL name UUID rejection applies.
        assertThatNoException().isThrownBy(() ->
            io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("connector/a81b4c6d-1234-5678-abcd-ef0123456789"));
    }

    // ── Dot-notation: specific error with suggestions ──

    @Test void rejectsDotNotation_singleSegment() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("quarkmind.scouting.intel"))
            .withMessageContaining("quarkmind-scouting-intel")
            .withMessageContaining("quarkmind/scouting/intel");
    }

    @Test void rejectsDotNotation_inSecondPathSegment() {
        // Split on '/': first segment "quarkmind" passes; second "scouting.intel" triggers dot error
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("quarkmind/scouting.intel"))
            .withMessageContaining("scouting-intel")
            .withMessageContaining("scouting/intel");
    }

    @Test void rejectsUppercase_usesGenericError() {
        // Non-dot failure must NOT include dot-specific suggestions
        assertThatIllegalArgumentException()
            .isThrownBy(() -> io.casehub.qhorus.api.channel.ChannelSlugValidator.validateSlugPath("Billing-Output"))
            .withMessageNotContaining("dots are not valid")
            .withMessageContaining("Billing-Output");
    }

    // ── isValidSegment ──

    @Test void isValidSegment_trueForValidSlug() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.isValidSegment("billing-output")).isTrue();
    }

    @Test void isValidSegment_falseForNull() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.isValidSegment(null)).isFalse();
    }

    @Test void isValidSegment_falseForTrailingHyphen() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.isValidSegment("billing-")).isFalse();
    }

    @Test void isValidSegment_falseForUuidShaped() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.isValidSegment("a81b4c6d-1234-5678-abcd-ef0123456789")).isFalse();
    }

    @Test void isValidSegment_falseForSegmentOver80Chars() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.isValidSegment("a" + "b".repeat(80))).isFalse();
    }

    // ── tryParseUuid ──

    @Test void tryParseUuid_returnsUuidForValidInput() {
        UUID uuid = io.casehub.qhorus.api.channel.ChannelSlugValidator.tryParseUuid("a81b4c6d-1234-5678-abcd-ef0123456789");
        assertThat(uuid).isNotNull();
        assertThat(uuid.toString()).isEqualTo("a81b4c6d-1234-5678-abcd-ef0123456789");
    }

    @Test void tryParseUuid_returnsNullForNonUuid() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.tryParseUuid("billing-output")).isNull();
    }

    @Test void tryParseUuid_returnsNullForNull() {
        assertThat(io.casehub.qhorus.api.channel.ChannelSlugValidator.tryParseUuid(null)).isNull();
    }
}
