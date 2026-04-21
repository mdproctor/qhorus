package io.quarkiverse.qhorus.runtime.ledger;

import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.LedgerEntryType;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link LedgerWriteService}. Writes structured audit ledger entries for EVENT
 * messages using the reactive ledger repository. Called from {@code ReactiveQhorusMcpTools} (#78).
 *
 * <p>
 * Uses {@code Panache.withTransaction()} rather than {@code @Transactional(REQUIRES_NEW)} — ledger
 * write failures must be caught and swallowed at the call site to maintain message pipeline integrity.
 */
@Alternative
@ApplicationScoped
public class ReactiveLedgerWriteService {

    private static final Logger LOG = Logger.getLogger(ReactiveLedgerWriteService.class);

    @Inject
    ReactiveAgentMessageLedgerEntryRepository reactiveRepo;

    @Inject
    LedgerConfig config;

    @Inject
    ObjectMapper objectMapper;

    public Uni<Void> recordEvent(final Channel ch, final Message message) {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        final String content = message.content;
        if (content == null || !content.stripLeading().startsWith("{")) {
            return Uni.createFrom().voidItem();
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (Exception e) {
            LOG.warnf("ReactiveLedgerWriteService: could not parse EVENT content as JSON for message %d — skipping. Error: %s",
                    message.id, e.getMessage());
            return Uni.createFrom().voidItem();
        }

        final JsonNode toolNameNode = root.get("tool_name");
        final JsonNode durationMsNode = root.get("duration_ms");

        if (toolNameNode == null || toolNameNode.isNull() || !toolNameNode.isTextual()) {
            LOG.warnf("ReactiveLedgerWriteService: EVENT message %d missing mandatory 'tool_name' — skipping ledger entry",
                    message.id);
            return Uni.createFrom().voidItem();
        }
        if (durationMsNode == null || durationMsNode.isNull() || !durationMsNode.isNumber()) {
            LOG.warnf("ReactiveLedgerWriteService: EVENT message %d missing mandatory 'duration_ms' — skipping ledger entry",
                    message.id);
            return Uni.createFrom().voidItem();
        }

        final String toolName = toolNameNode.asText();
        final long durationMs = durationMsNode.asLong();

        Long tokenCount = null;
        final JsonNode tokenCountNode = root.get("token_count");
        if (tokenCountNode != null && !tokenCountNode.isNull() && tokenCountNode.isNumber()) {
            tokenCount = tokenCountNode.asLong();
        }

        String contextRefs = null;
        final JsonNode contextRefsNode = root.get("context_refs");
        if (contextRefsNode != null && !contextRefsNode.isNull()) {
            try {
                contextRefs = objectMapper.writeValueAsString(contextRefsNode);
            } catch (Exception e) {
                LOG.warnf("ReactiveLedgerWriteService: could not serialize context_refs for message %d", message.id);
            }
        }

        String sourceEntity = null;
        final JsonNode sourceEntityNode = root.get("source_entity");
        if (sourceEntityNode != null && !sourceEntityNode.isNull()) {
            try {
                sourceEntity = objectMapper.writeValueAsString(sourceEntityNode);
            } catch (Exception e) {
                LOG.warnf("ReactiveLedgerWriteService: could not serialize source_entity for message %d", message.id);
            }
        }

        final Long finalTokenCount = tokenCount;
        final String finalContextRefs = contextRefs;
        final String finalSourceEntity = sourceEntity;

        return Panache.withTransaction(() -> reactiveRepo.findLatestBySubjectId(ch.id).flatMap(latestOpt -> {
            final int sequenceNumber = latestOpt.map(e -> e.sequenceNumber + 1).orElse(1);

            final AgentMessageLedgerEntry entry = new AgentMessageLedgerEntry();
            entry.subjectId = ch.id;
            entry.channelId = ch.id;
            entry.messageId = message.id;
            entry.toolName = toolName;
            entry.durationMs = durationMs;
            entry.tokenCount = finalTokenCount;
            entry.contextRefs = finalContextRefs;
            entry.sourceEntity = finalSourceEntity;
            entry.actorId = message.sender;
            entry.actorType = ActorType.AGENT;
            entry.entryType = LedgerEntryType.EVENT;
            entry.occurredAt = message.createdAt.truncatedTo(ChronoUnit.MILLIS);
            entry.sequenceNumber = sequenceNumber;
            if (message.correlationId != null) {
                entry.correlationId = message.correlationId;
            }

            return reactiveRepo.save(entry).replaceWithVoid();
        }));
    }
}
