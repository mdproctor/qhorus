package io.casehub.qhorus.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.platform.api.credentials.CredentialPropertyKeys;
import io.casehub.platform.api.credentials.CredentialResolver;
import io.casehub.qhorus.api.gateway.MessageObserver;
import io.casehub.qhorus.api.gateway.MessageReceivedEvent;
import io.casehub.qhorus.api.message.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookMessageObserverTest {

    record PostRecord(String url, String body, String secret, Map<String, String> headers) {}

    private static final String                 TENANT = "t1";
    private              WebhookRegistry        registry;
    private              WebhookMessageObserver observer;
    private final        List<PostRecord>       posts  = new ArrayList<>();

    private final CredentialResolver credentialResolver = ref -> {
        if ("valid-cred".equals(ref)) {
            return Map.of(CredentialPropertyKeys.SIGNING_SECRET, "resolved-signing-secret");
        }
        if ("no-signing-key".equals(ref)) {
            return Map.of(CredentialPropertyKeys.BEARER_TOKEN, "some-token");
        }
        throw new java.util.NoSuchElementException("Unknown credential: " + ref);
    };

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        registry = new WebhookRegistry();
        observer = new WebhookMessageObserver(mapper, registry, credentialResolver,
                                              (url, body, secret, headers) -> posts.add(new PostRecord(url, body, secret, headers)));
    }

    @Test
    void scopeIsCluster() {
        assertThat(observer.scope()).isEqualTo(MessageObserver.Scope.CLUSTER);
    }

    @Test
    void postsToRegisteredWebhook() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://example.com/hook", null, Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).url()).isEqualTo("https://example.com/hook");
        assertThat(posts.get(0).body()).isNotEmpty();
    }

    @Test
    void postsToGlobalWebhookForSameTenant() {
        registry.registerInMemory(null, TENANT, "https://example.com/global", null, Map.of());

        UUID channelId = UUID.randomUUID();
        observer.onMessage(event(channelId, MessageType.QUERY, "q"));

        assertThat(posts).hasSize(1);
    }

    @Test
    void globalWebhookDoesNotFireForDifferentTenant() {
        registry.registerInMemory(null, "other-tenant", "https://example.com/global", null, Map.of());

        UUID channelId = UUID.randomUUID();
        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).isEmpty();
    }

    @Test
    void secretRefResolvesViaCredentialResolver() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://example.com/hook", "valid-cred", Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).secret()).isEqualTo("resolved-signing-secret");
    }

    @Test
    void missingCredentialSkipsPost() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://example.com/hook", "missing-cred", Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).isEmpty();
    }

    @Test
    void credentialWithoutSigningSecretKeySkipsPost() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://example.com/hook", "no-signing-key", Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).isEmpty();
    }

    @Test
    void noSecretRefPostsWithoutSignature() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://example.com/hook", null, Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).secret()).isNull();
    }

    @Test
    void noPostWhenNoRegistrations() {
        UUID channelId = UUID.randomUUID();
        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).isEmpty();
    }

    @Test
    void hmacSha256ProducesDeterministicOutput() {
        String sig1 = WebhookMessageObserver.hmacSha256("secret", "data");
        String sig2 = WebhookMessageObserver.hmacSha256("secret", "data");
        assertThat(sig1).isEqualTo(sig2);
        assertThat(sig1).hasSize(64);
    }

    @Test
    void hmacSha256DifferentSecretProducesDifferentHash() {
        String sig1 = WebhookMessageObserver.hmacSha256("secret1", "data");
        String sig2 = WebhookMessageObserver.hmacSha256("secret2", "data");
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void postsToMultipleHooks() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://a.com/hook", null, Map.of());
        registry.registerInMemory(channelId, TENANT, "https://b.com/hook", null, Map.of());
        registry.registerInMemory(null, TENANT, "https://global.com/hook", null, Map.of());

        observer.onMessage(event(channelId, MessageType.DONE, "done"));

        assertThat(posts).hasSize(3);
    }

    @Test
    void failedCredentialDoesNotBlockOtherHooks() {
        UUID channelId = UUID.randomUUID();
        registry.registerInMemory(channelId, TENANT, "https://a.com/hook", "missing-cred", Map.of());
        registry.registerInMemory(channelId, TENANT, "https://b.com/hook", null, Map.of());

        observer.onMessage(event(channelId, MessageType.STATUS, "hello"));

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).url()).isEqualTo("https://b.com/hook");
    }

    private MessageReceivedEvent event(UUID channelId, MessageType type, String content) {
        return new MessageReceivedEvent(
                1L, "test-channel", channelId, TENANT,
                type, "agent-1", null,
                Instant.now(), content, null);
    }
}
