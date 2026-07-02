package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.twilio.TwilioSmsConnector;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.store.ChannelBindingStore;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@link ChannelService#findOrCreateWithBinding(io.casehub.qhorus.api.channel.ChannelCreateRequest)}.
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
        String phone = uniquePhone();
        FindOrCreateResult result = channelService.findOrCreateWithBinding(smsRequest(phone));

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
        String phone = uniquePhone();
        FindOrCreateResult first  = channelService.findOrCreateWithBinding(smsRequest(phone));
        FindOrCreateResult second = channelService.findOrCreateWithBinding(smsRequest(phone));

        assertThat(first.wasCreated()).isTrue();
        assertThat(second.wasCreated()).isFalse();
        assertThat(second.channel().id()).isEqualTo(first.channel().id());
        assertThat(channelService.findByConnectorKey(InboundConnectorIds.TWILIO_SMS, phone))
                .isPresent();
    }

    @Test
    void differentSenders_createSeparateChannels() {
        String phone1 = uniquePhone();
        String phone2 = uniquePhone();
        FindOrCreateResult r1 = channelService.findOrCreateWithBinding(smsRequest(phone1));
        FindOrCreateResult r2 = channelService.findOrCreateWithBinding(smsRequest(phone2));

        assertThat(r1.wasCreated()).isTrue();
        assertThat(r2.wasCreated()).isTrue();
        assertThat(r1.channel().id()).isNotEqualTo(r2.channel().id());
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone1)).isPresent();
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone2)).isPresent();
    }

    @Test
    void throwsWhenNoConnectorBinding() {
        // Use a valid slug name — no connector binding fields → should throw IAE
        String                                             validName = "my-channel-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        io.casehub.qhorus.api.channel.ChannelCreateRequest noBinding = io.casehub.qhorus.api.channel.ChannelCreateRequest.builder(validName).build();
        assertThatThrownBy(() -> channelService.findOrCreateWithBinding(noBinding))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector binding");
    }
}
