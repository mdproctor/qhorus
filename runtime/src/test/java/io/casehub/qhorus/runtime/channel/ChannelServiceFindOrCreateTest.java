package io.casehub.qhorus.runtime.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import io.casehub.connectors.InboundConnectorIds;
import io.casehub.connectors.twilio.TwilioSmsConnector;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.runtime.store.ChannelBindingStore;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@link ChannelService#findOrCreateWithBinding(ChannelCreateRequest)}.
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

    private ChannelCreateRequest smsRequest(String senderPhone) {
        return new ChannelCreateRequest(
                "connector/twilio-sms-inbound/" + senderPhone,
                "Auto-created on first contact",
                ChannelSemantic.APPEND,
                null, null, null, null, null, null,
                InboundConnectorIds.TWILIO_SMS, senderPhone, TwilioSmsConnector.ID, senderPhone);
    }

    private String uniquePhone() {
        return "+44" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    void createsChannelAndBindingWhenNotFound() {
        String phone = uniquePhone();
        Channel result = channelService.findOrCreateWithBinding(smsRequest(phone));

        assertThat(result).isNotNull();
        assertThat(result.id).isNotNull();
        assertThat(result.name).isEqualTo("connector/twilio-sms-inbound/" + phone);
        assertThat(result.autoCreated).isTrue();
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone)).isPresent();
    }

    @Test
    void returnsExistingChannelWhenAlreadyCreated() {
        String phone = uniquePhone();
        Channel first = channelService.findOrCreateWithBinding(smsRequest(phone));
        Channel second = channelService.findOrCreateWithBinding(smsRequest(phone));

        assertThat(second.id).isEqualTo(first.id);
        assertThat(channelService.findByConnectorKey(InboundConnectorIds.TWILIO_SMS, phone))
                .isPresent();
    }

    @Test
    void setsAutoCreatedTrue() {
        String phone = uniquePhone();
        Channel result = channelService.findOrCreateWithBinding(smsRequest(phone));
        assertThat(result.autoCreated).isTrue();
    }

    @Test
    void differentSenders_createSeparateChannels() {
        String phone1 = uniquePhone();
        String phone2 = uniquePhone();
        Channel ch1 = channelService.findOrCreateWithBinding(smsRequest(phone1));
        Channel ch2 = channelService.findOrCreateWithBinding(smsRequest(phone2));

        assertThat(ch1.id).isNotEqualTo(ch2.id);
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone1)).isPresent();
        assertThat(channelBindingStore.findByKey(InboundConnectorIds.TWILIO_SMS, phone2)).isPresent();
    }

    @Test
    void throwsWhenNoConnectorBinding() {
        ChannelCreateRequest noBinding = ChannelCreateRequest.simple("my-channel-" + uniquePhone(), ChannelSemantic.APPEND);
        assertThatThrownBy(() -> channelService.findOrCreateWithBinding(noBinding))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
