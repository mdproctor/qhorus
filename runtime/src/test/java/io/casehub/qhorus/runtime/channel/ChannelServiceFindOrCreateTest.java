package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.twilio.TwilioSmsConnector;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@link ChannelService#findOrCreate(io.casehub.qhorus.api.channel.ChannelCreateRequest)}.
 *
 * <p>Each test uses a unique phone number to avoid cross-test state issues.
 * The method uses REQUIRES_NEW which commits independently of any surrounding context,
 * so @TestTransaction isolation is not possible here.
 *
 * Refs #214
 */
@QuarkusTest
class ChannelServiceFindOrCreateTest {

    @Inject ChannelService channelService;
    @Inject ChannelBindingStore channelBindingStore;

    private io.casehub.qhorus.api.channel.ChannelCreateRequest smsRequest(String senderPhone) {
        // Channel name must be a valid slug — phone digits only, prefixed with "tel-"
        // to avoid a digit-starting segment. The raw senderPhone is still used as the
        // externalKey (binding lookup key — not slug-validated).
        String digits = senderPhone.replaceAll("[^0-9]", "");
        String channelName = "connector/twilio-sms-inbound/tel-" + digits;
        return new io.casehub.qhorus.api.channel.ChannelCreateRequest(
                channelName,
                "Auto-created on first contact",
                ChannelSemantic.APPEND,
                null, null, null, null, null, null, null,
                InboundConnectorIds.TWILIO_SMS, senderPhone, TwilioSmsConnector.ID, senderPhone);
    }

    private String uniquePhone() {
        return "+44" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    void createsChannelAndBindingWhenNotFound() {
        String                                           phone  = uniquePhone();
        io.casehub.qhorus.api.channel.FindOrCreateResult result = channelService.findOrCreate(smsRequest(phone));

        assertThat(result.wasCreated()).isTrue();
        assertThat(result.channel()).isNotNull();
        assertThat(result.channel().id()).isNotNull();
        // Name uses digits-only phone segment prefixed with "tel-" (slug-valid)
        String digits = phone.replaceAll("[^0-9]", "");
        assertThat(result.channel().name()).isEqualTo("connector/twilio-sms-inbound/tel-" + digits);
        assertThat(result.channel().autoCreated()).isTrue();
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone)).isPresent();
    }

    @Test
    void returnsExistingChannelWhenAlreadyCreated() {
        String                                           phone  = uniquePhone();
        io.casehub.qhorus.api.channel.FindOrCreateResult first  = channelService.findOrCreate(smsRequest(phone));
        io.casehub.qhorus.api.channel.FindOrCreateResult second = channelService.findOrCreate(smsRequest(phone));

        assertThat(first.wasCreated()).isTrue();
        assertThat(second.wasCreated()).isFalse();
        assertThat(second.channel().id()).isEqualTo(first.channel().id());
        assertThat(channelService.findByConnectorKey(InboundConnectorIds.TWILIO_SMS, phone))
                .isPresent();
    }

    @Test
    void differentSenders_createSeparateChannels() {
        String phone1 = uniquePhone();
        String                                           phone2 = uniquePhone();
        io.casehub.qhorus.api.channel.FindOrCreateResult r1     = channelService.findOrCreate(smsRequest(phone1));
        io.casehub.qhorus.api.channel.FindOrCreateResult r2     = channelService.findOrCreate(smsRequest(phone2));

        assertThat(r1.wasCreated()).isTrue();
        assertThat(r2.wasCreated()).isTrue();
        assertThat(r1.channel().id()).isNotEqualTo(r2.channel().id());
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone1)).isPresent();
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone2)).isPresent();
    }

    @Test
    void findOrCreate_nameBasedLookup_createsNew() {
        String validName = "test-name-based-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        io.casehub.qhorus.api.channel.ChannelCreateRequest req =
                io.casehub.qhorus.api.channel.ChannelCreateRequest.builder(validName).build();
        io.casehub.qhorus.api.channel.FindOrCreateResult result = channelService.findOrCreate(req);

        assertThat(result.wasCreated()).isTrue();
        assertThat(result.channel()).isNotNull();
        assertThat(result.channel().name()).isEqualTo(validName);
    }

    @Test
    void findOrCreate_nameBasedLookup_findsExisting() {
        String validName = "test-find-existing-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        io.casehub.qhorus.api.channel.ChannelCreateRequest req =
                io.casehub.qhorus.api.channel.ChannelCreateRequest.builder(validName).build();
        channelService.create(req);

        io.casehub.qhorus.api.channel.FindOrCreateResult result = channelService.findOrCreate(req);

        assertThat(result.wasCreated()).isFalse();
        assertThat(result.channel().name()).isEqualTo(validName);
    }

    @Test
    void findOrCreate_nameBasedRace_recoversGracefully() {
        // Simulate the race: pre-create a channel, then call findOrCreate with the same name.
        // The create inside findOrCreate will hit the unique constraint.
        // On PostgreSQL this aborts the transaction — the fix uses nested REQUIRES_NEW
        // so the retry query runs in the still-clean outer transaction.
        String validName = "test-race-recovery-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        io.casehub.qhorus.api.channel.ChannelCreateRequest req =
                io.casehub.qhorus.api.channel.ChannelCreateRequest.builder(validName).build();

        // Pre-create the channel in a separate committed transaction
        channelService.create(req);

        // findOrCreate should find the existing channel, not throw
        io.casehub.qhorus.api.channel.FindOrCreateResult result = channelService.findOrCreate(req);

        assertThat(result.wasCreated()).isFalse();
        assertThat(result.channel().name()).isEqualTo(validName);
    }
}
