package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.InstanceActorIdProvider;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

/**
 * Reactive mirror of {@link LedgerWriteService}.
 *
 * <p>
 * Writes immutable audit ledger entries for every message type using the reactive ledger
 * repository. Signature-aligned with the blocking {@link LedgerWriteService#record} — accepts
 * {@link MessageDispatch} (not Channel+Message entities) and returns {@link LedgerWriteOutcome}.
 *
 * <p>
 * {@code subjectId} resolution follows the same 3-priority chain as the blocking service:
 * <ol>
 * <li>Explicit — {@code dispatch.subjectId()} when non-null</li>
 * <li>Correlation root — earliest entry in the {@code correlationId} thread with a non-null
 *     {@code subjectId} (cross-channel by design)</li>
 * <li>Fallback — {@code dispatch.channelId()} (always non-null)</li>
 * </ol>
 *
 * <p>
 * {@code causedByEntryId} resolution follows the same 2-priority chain:
 * <ol>
 * <li>Explicit — {@code dispatch.causedByEntryId()} when non-null</li>
 * <li>inReplyTo lookup — ledger entry whose {@code messageId = dispatch.inReplyTo()}</li>
 * </ol>
 *
 * <p>
 * For DONE, FAILURE, and DECLINE: a {@link LedgerAttestation} is written against
 * the causally-linked COMMAND/HANDOFF entry when {@code causedByEntryId} is non-null.
 * Attestation failures are caught and logged — the trust signal is lost but the
 * pipeline is unaffected.
 *
 * <p>
 * Refs casehubio/ledger#105, qhorus#234, Epic #99.
 */
@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveLedgerWriteService {

    private static final Logger LOG = Logger.getLogger(ReactiveLedgerWriteService.class);
    private static final Set<MessageType> ATTESTATION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);

    @Inject
    ReactiveMessageLedgerEntryRepository reactiveRepo;

    @Inject
    LedgerConfig config;

    @Inject
    public InstanceActorIdProvider actorIdProvider;

    @Inject
    public CommitmentAttestationPolicy attestationPolicy;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Record the given dispatch as an immutable ledger entry via the reactive stack.
     *
     * @param dispatch     the plain-record dispatch carrying all message fields (no JPA entities)
     * @param messageId    the surrogate Long PK of the persisted {@code Message} entity
     * @param commitmentId the UUID of the associated commitment, or null if none
     * @param occurredAt   the wall-clock time the message was dispatched
     * @return {@link LedgerWriteOutcome} with resolved values; or {@link LedgerWriteOutcome#DISABLED}
     *         when ledger writes are suppressed via config.
     */
    public Uni<LedgerWriteOutcome> record(final MessageDispatch dispatch,
            final Long messageId,
            @Nullable final UUID commitmentId,
            final Instant occurredAt) {
        if (!config.enabled()) {
            return Uni.createFrom().item(LedgerWriteOutcome.DISABLED);
        }

        // Note: when called from ReactiveMessageService.dispatch(), this withTransaction joins
        // the enclosing message-insert transaction rather than creating REQUIRES_NEW semantics
        // (Hibernate Reactive has no equivalent to JTA's REQUIRES_NEW). This means the ledger
        // entry is always atomic with the message insert — either both commit or neither does.
        // This differs intentionally from the blocking LedgerWriteService which uses REQUIRES_NEW
        // so ledger entries survive outer transaction failures. The reactive behaviour (strict
        // atomicity) is the chosen tradeoff for simpler semantics in reactive contexts.
        return Panache.withTransaction("qhorus", () -> resolveSubjectId(dispatch)
                .flatMap(resolvedSubjectId -> resolveCausedByEntryId(dispatch)
                        .flatMap(resolvedCausedByEntryId -> reactiveRepo.findLatestBySubjectId(resolvedSubjectId)
                                .flatMap(latestOpt -> {
                                    final int sequenceNumber = latestOpt.map(e -> ((MessageLedgerEntry) e).sequenceNumber + 1)
                                            .orElse(1);

                                    final String resolvedActorId = actorIdProvider.resolve(dispatch.sender());

                                    final MessageLedgerEntry entry = new MessageLedgerEntry();
                                    entry.subjectId = resolvedSubjectId;
                                    entry.channelId = dispatch.channelId();
                                    entry.messageId = messageId;
                                    entry.commitmentId = commitmentId;
                                    entry.causedByEntryId = resolvedCausedByEntryId;
                                    entry.messageType = dispatch.type().name();
                                    entry.target = dispatch.target();
                                    entry.correlationId = dispatch.correlationId();
                                    entry.actorId = resolvedActorId;
                                    entry.actorType = dispatch.actorType();
                                    entry.occurredAt = occurredAt.truncatedTo(ChronoUnit.MILLIS);
                                    entry.sequenceNumber = sequenceNumber;
                                    entry.entryType = switch (dispatch.type()) {
                                        case QUERY, COMMAND, HANDOFF -> LedgerEntryType.COMMAND;
                                        default -> LedgerEntryType.EVENT;
                                    };

                                    if (dispatch.type() == MessageType.EVENT) {
                                        populateTelemetry(entry, dispatch.content());
                                    } else {
                                        entry.content = dispatch.content();
                                    }

                                    return reactiveRepo.save(entry)
                                            .flatMap(saved -> {
                                                final LedgerWriteOutcome outcome = new LedgerWriteOutcome(
                                                        saved.id, resolvedSubjectId, resolvedCausedByEntryId);
                                                if (ATTESTATION_TYPES.contains(dispatch.type())
                                                        && resolvedCausedByEntryId != null) {
                                                    return writeAttestation(resolvedSubjectId,
                                                            resolvedCausedByEntryId, dispatch.type(),
                                                            resolvedActorId)
                                                            .replaceWith(outcome);
                                                }
                                                return Uni.createFrom().item(outcome);
                                            });
                                }))));
    }

    private Uni<Void> writeAttestation(final UUID subjectId, final UUID causedByEntryId,
            final MessageType type, final String actorId) {
        return reactiveRepo.findEntryById(causedByEntryId)
                .flatMap(priorOpt -> {
                    if (priorOpt.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    final MessageLedgerEntry prior = (MessageLedgerEntry) priorOpt.get();
                    if (!"COMMAND".equals(prior.messageType) && !"HANDOFF".equals(prior.messageType)) {
                        return Uni.createFrom().voidItem();
                    }
                    final var outcomeOpt = attestationPolicy.attestationFor(type, actorId);
                    if (outcomeOpt.isEmpty()) {
                        return Uni.createFrom().voidItem();
                    }
                    final var outcome = outcomeOpt.get();
                    final LedgerAttestation attestation = new LedgerAttestation();
                    attestation.ledgerEntryId = prior.id;
                    attestation.subjectId = subjectId;
                    attestation.attestorId = outcome.attestorId();
                    attestation.attestorType = outcome.attestorType();
                    attestation.verdict = outcome.verdict();
                    attestation.confidence = outcome.confidence();
                    attestation.capabilityTag = extractCapabilityTag(prior.content);
                    return reactiveRepo.saveAttestation(attestation)
                            .invoke(a -> LOG.debugf(
                                    "LedgerAttestation %s written for COMMAND entry %s (correlationId='%s', capability='%s')",
                                    a.verdict, prior.id, prior.correlationId, a.capabilityTag))
                            .replaceWithVoid();
                })
                .onFailure().recoverWithUni(e -> {
                    LOG.warnf("Could not write attestation for entry %s — trust signal lost but pipeline unaffected",
                            causedByEntryId);
                    return Uni.createFrom().voidItem();
                });
    }

    private String extractCapabilityTag(final String content) {
        if (content == null || !content.stripLeading().startsWith("{")) {
            return CapabilityTag.GLOBAL;
        }
        try {
            final JsonNode root = objectMapper.readTree(content);
            final JsonNode cap = root.get("capability");
            if (cap != null && cap.isTextual() && !cap.asText().isBlank()) {
                return cap.asText();
            }
        } catch (final Exception ignored) {
        }
        return CapabilityTag.GLOBAL;
    }

    // ── Priority 1/2/3 subjectId resolution ──────────────────────────────────

    private Uni<UUID> resolveSubjectId(final MessageDispatch dispatch) {
        if (dispatch.subjectId() != null) {
            return Uni.createFrom().item(dispatch.subjectId());
        }
        if (dispatch.correlationId() != null) {
            return reactiveRepo.findEarliestWithSubjectByCorrelationId(dispatch.correlationId())
                    .map(opt -> opt.map(e -> e.subjectId).orElse(dispatch.channelId()));
        }
        return Uni.createFrom().item(dispatch.channelId());
    }

    // ── Priority 1/2 causedByEntryId resolution ──────────────────────────────

    private Uni<UUID> resolveCausedByEntryId(final MessageDispatch dispatch) {
        if (dispatch.causedByEntryId() != null) {
            return Uni.createFrom().item(dispatch.causedByEntryId());
        }
        if (dispatch.inReplyTo() != null) {
            return reactiveRepo.findByMessageId(dispatch.inReplyTo())
                    .map(opt -> opt.map(e -> e.id).orElse(null));
        }
        return Uni.createFrom().nullItem();
    }

    private void populateTelemetry(final MessageLedgerEntry entry, final String content) {
        if (content == null || !content.stripLeading().startsWith("{")) {
            return;
        }
        try {
            final JsonNode root = objectMapper.readTree(content);
            final JsonNode tn = root.get("tool_name");
            if (tn != null && tn.isTextual()) {
                entry.toolName = tn.asText();
            }
            final JsonNode dm = root.get("duration_ms");
            if (dm != null && dm.isNumber()) {
                entry.durationMs = dm.asLong();
            }
            final JsonNode tc = root.get("token_count");
            if (tc != null && tc.isNumber()) {
                entry.tokenCount = tc.asLong();
            }
            final JsonNode cr = root.get("context_refs");
            if (cr != null && !cr.isNull()) {
                try {
                    entry.contextRefs = objectMapper.writeValueAsString(cr);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise context_refs for reactive ledger entry on message %d",
                            entry.messageId);
                }
            }
            final JsonNode se = root.get("source_entity");
            if (se != null && !se.isNull()) {
                try {
                    entry.sourceEntity = objectMapper.writeValueAsString(se);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise source_entity for reactive ledger entry on message %d",
                            entry.messageId);
                }
            }
        } catch (final Exception e) {
            LOG.warnf("Could not parse EVENT content as JSON for reactive message %d — telemetry fields will be null",
                    entry.messageId);
        }
    }
}
