package io.casehub.qhorus.runtime.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

/**
 * Writes immutable audit ledger entries for every message dispatched on a channel.
 *
 * <p>
 * Called from {@code MessageService.dispatch()} for all 9 message types — no conditional
 * branching in the caller. Every speech act on a channel is permanently recorded as a
 * {@link MessageLedgerEntry}. The CommitmentStore is the live obligation state; this ledger
 * is the tamper-evident historical record.
 *
 * <p>
 * For EVENT messages, telemetry fields ({@code toolName}, {@code durationMs}, etc.) are
 * extracted from the JSON payload. Malformed or partial payloads still produce an entry
 * — the speech act happened regardless of telemetry quality. All other types store
 * {@code dispatch.content()} verbatim in the {@code content} field.
 *
 * <p>
 * {@code subjectId} resolution (priority order):
 * <ol>
 * <li>Explicit — {@code dispatch.subjectId()} when non-null</li>
 * <li>Correlation root — earliest entry in the {@code correlationId} thread with a non-null
 *     {@code subjectId} (cross-channel by design: a domain subject spans all channels in
 *     a multi-channel handoff flow)</li>
 * <li>Fallback — {@code dispatch.channelId()} (always non-null)</li>
 * </ol>
 *
 * <p>
 * {@code causedByEntryId} resolution (priority order):
 * <ol>
 * <li>Explicit — {@code dispatch.causedByEntryId()} when non-null</li>
 * <li>inReplyTo lookup — ledger entry whose {@code messageId = dispatch.inReplyTo()}</li>
 * <li>null — no causal predecessor</li>
 * </ol>
 *
 * <p>
 * For DONE, FAILURE, and DECLINE: a {@link LedgerAttestation} is written against the
 * causally-linked COMMAND entry when {@code causedByEntryId} is resolved and non-null.
 * Verdict and confidence feed the Bayesian Beta trust score in casehub-ledger. The
 * CommitmentStore is NOT queried here — attestation verdict is derived from
 * {@link MessageType} directly, avoiding a transaction-visibility bug (the outer
 * transaction's commitment update is not yet committed when this {@code REQUIRES_NEW}
 * transaction runs).
 *
 * <p>
 * The {@code actorId} on each entry is resolved through {@link InstanceActorIdProvider}
 * to map session-scoped instanceIds to persona-scoped ledger actorIds.
 *
 * <p>
 * Write failures propagate as exceptions — they are never caught here. The caller's
 * {@code @Transactional} boundary will roll back if this method throws.
 *
 * <p>
 * Refs #102, #123, #124, #184, Epic #99.
 */
@ApplicationScoped
public class LedgerWriteService {

    private static final Logger LOG = Logger.getLogger(LedgerWriteService.class);
    private static final Set<MessageType> ATTESTATION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE);

    @Inject
    public MessageLedgerEntryRepository repository;

    @Inject
    public LedgerConfig config;

    @Inject
    public InstanceActorIdProvider actorIdProvider;

    @Inject
    public CommitmentAttestationPolicy attestationPolicy;

    @Inject
    public ObjectMapper objectMapper;

    /**
     * Record the given dispatch as an immutable ledger entry.
     *
     * <p>Runs in its own transaction ({@code REQUIRES_NEW}). The caller MUST extract all values
     * from JPA entities before calling — no entities cross this transaction boundary.
     *
     * <p>Write failures propagate as exceptions — they are never caught here. The caller's
     * {@code @Transactional} boundary will roll back if this method throws.
     *
     * @param dispatch the plain-record dispatch carrying all message fields (no JPA entities)
     * @param messageId the surrogate Long PK of the persisted {@code Message} entity
     * @param commitmentId the UUID of the associated commitment, or null if none
     * @return {@link LedgerWriteOutcome} with resolved values; or {@link LedgerWriteOutcome#DISABLED}
     *         when ledger writes are suppressed via config.
     */
    @Transactional(value = Transactional.TxType.REQUIRES_NEW)
    public LedgerWriteOutcome record(final MessageDispatch dispatch,
            final Long messageId,
            @Nullable final UUID commitmentId,
            final Instant occurredAt) {
        if (!config.enabled()) {
            return LedgerWriteOutcome.DISABLED;
        }

        // ── Resolve subjectId (Priority 1 > 2 > 3) ───────────────────────────────
        final UUID resolvedSubjectId;
        if (dispatch.subjectId() != null) {
            resolvedSubjectId = dispatch.subjectId();
        } else if (dispatch.correlationId() != null) {
            resolvedSubjectId = repository
                    .findEarliestWithSubjectByCorrelationId(dispatch.correlationId())
                    .map(e -> e.subjectId)
                    .orElse(dispatch.channelId());
        } else {
            resolvedSubjectId = dispatch.channelId();
        }

        // ── Resolve causedByEntryId (Priority 1 > 2 > null) ─────────────────────
        final UUID resolvedCausedByEntryId;
        if (dispatch.causedByEntryId() != null) {
            resolvedCausedByEntryId = dispatch.causedByEntryId();
        } else if (dispatch.inReplyTo() != null) {
            resolvedCausedByEntryId = repository.findByMessageId(dispatch.inReplyTo())
                    .map(e -> e.id)
                    .orElse(null);
        } else {
            resolvedCausedByEntryId = null;
        }

        // ── Sequence number (per resolved subject chain) ──────────────────────────
        final int sequenceNumber = repository.findLatestBySubjectId(resolvedSubjectId)
                .map(e -> e.sequenceNumber + 1).orElse(1);

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

        // ── Attestation for terminal commitment types ─────────────────────────────
        // Guard: only attest against COMMAND or HANDOFF entries — not STATUS, RESPONSE, etc.
        if (ATTESTATION_TYPES.contains(dispatch.type()) && resolvedCausedByEntryId != null) {
            repository.findEntryById(resolvedCausedByEntryId).ifPresent(prior -> {
                final MessageLedgerEntry priorEntry = (MessageLedgerEntry) prior;
                if ("COMMAND".equals(priorEntry.messageType) || "HANDOFF".equals(priorEntry.messageType)) {
                    writeAttestation(resolvedSubjectId, priorEntry, dispatch.type(), resolvedActorId);
                }
            });
        }

        repository.save(entry);
        return new LedgerWriteOutcome(entry.id, resolvedSubjectId, resolvedCausedByEntryId);
    }

    private void writeAttestation(final UUID subjectId, final MessageLedgerEntry commandEntry,
            final MessageType terminalType, final String resolvedActorId) {
        attestationPolicy.attestationFor(terminalType, resolvedActorId).ifPresent(outcome -> {
            try {
                final LedgerAttestation attestation = new LedgerAttestation();
                attestation.ledgerEntryId = commandEntry.id;
                attestation.subjectId = subjectId;
                attestation.attestorId = outcome.attestorId();
                attestation.attestorType = outcome.attestorType();
                attestation.verdict = outcome.verdict();
                attestation.confidence = outcome.confidence();
                attestation.capabilityTag = extractCapabilityTag(commandEntry.content);
                repository.saveAttestation(attestation);
                LOG.debugf("LedgerAttestation %s written for COMMAND entry %s (correlationId='%s')",
                        attestation.verdict, commandEntry.id, commandEntry.correlationId);
            } catch (final Exception e) {
                LOG.warnf("Could not write attestation for entry %s — trust signal lost but pipeline unaffected",
                        commandEntry.id);
            }
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
            // malformed JSON — fall through to global
        }
        return CapabilityTag.GLOBAL;
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
                    LOG.warnf("Could not serialise context_refs for ledger entry on message %d",
                            entry.messageId);
                }
            }
            final JsonNode se = root.get("source_entity");
            if (se != null && !se.isNull()) {
                try {
                    entry.sourceEntity = objectMapper.writeValueAsString(se);
                } catch (final Exception ignored) {
                    LOG.warnf("Could not serialise source_entity for ledger entry on message %d",
                            entry.messageId);
                }
            }
        } catch (final Exception e) {
            LOG.warnf("Could not parse EVENT content as JSON for message %d — telemetry fields will be null",
                    entry.messageId);
        }
    }
}
