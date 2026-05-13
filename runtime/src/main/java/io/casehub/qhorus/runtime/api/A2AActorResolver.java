package io.casehub.qhorus.runtime.api;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.ledger.api.model.ActorType;
import io.casehub.ledger.api.model.ActorTypeResolver;
import io.casehub.qhorus.runtime.instance.InstanceService;
import io.quarkus.arc.properties.UnlessBuildProperty;

/**
 * Resolves {@link ActorType} for inbound A2A messages.
 *
 * <p>For {@code role:"agent"} — unconditional AGENT, chain skipped.
 * For {@code role:"user"} and unknown roles — 6-step chain:
 * explicit header, instance registry, agent card URL,
 * ActorTypeResolver on agentId (covers persona + system), default HUMAN.
 */
@ApplicationScoped
@UnlessBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true", enableIfMissing = true)
public class A2AActorResolver {

    @Inject
    InstanceService instanceService;

    public ActorType resolve(String role, String actorTypeHeader, Map<String, String> metadata) {
        // role:"agent" is unconditional — the chain does not apply
        if ("agent".equals(role)) {
            return ActorType.AGENT;
        }

        // Step 1: explicit x-qhorus-actor-type header
        if (actorTypeHeader != null && !actorTypeHeader.isBlank()) {
            try {
                return ActorType.valueOf(actorTypeHeader.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // invalid value — fall through to chain
            }
        }

        String agentId = metadata.get("agentId");

        // Step 2: Qhorus Instance registry lookup
        if (agentId != null && instanceService.findByInstanceId(agentId).isPresent()) {
            return ActorType.AGENT;
        }

        // Step 3: A2A Agent Card URL (survives relay, requires no Qhorus knowledge)
        String agentCardUrl = metadata.get("agentCardUrl");
        if (agentCardUrl != null && !agentCardUrl.isBlank()) {
            return ActorType.AGENT;
        }

        // Steps 4+5: delegate to canonical ActorTypeResolver (covers persona format + system:*)
        if (agentId != null) {
            ActorType fromId = ActorTypeResolver.resolve(agentId);
            if (fromId != ActorType.HUMAN) {
                return fromId;
            }
        }

        // Step 6: conservative default
        return ActorType.HUMAN;
    }
}
