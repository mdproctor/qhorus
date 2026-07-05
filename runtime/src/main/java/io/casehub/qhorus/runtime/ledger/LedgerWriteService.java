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
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentContext;
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
 * Ledger entry write failures propagate as exceptions — the caller's {@code @Transactional}
 * boundary will roll back if this method throws. Attestation write failures ({@code writeAttestation})
 * are caught and logged — attestation is a trust-scoring signal, not a correctness requirement.
 *
 * <p>
 * Refs #102, #123, #124, #184, Epic #99.
 */
@ApplicationScoped
public class LedgerWriteService {

    private static final Logger LOG = Logger.getLogger(LedgerWriteService.class);
    private static final Set<MessageType> ATTESTATION_TYPES = Set.of(
            MessageType.DONE, MessageType.FAILURE, MessageType.DECLINE, MessageType.RESPONSE);

    @Inject
    public LedgerEntryRepository ledger;

    @Inject
    public MessageLedgerEntryRepository messageRepo;

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
     * <p>Ledger entry write failures propagate — the caller's {@code @Transactional} will roll back.
     * Attestation write failures are caught and logged (see {@code writeAttestation}).
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

        // tenancyId resolved first — needed by messageRepo queries below. Refs qhorus#260, #263.
        final String tenancyId = dispatch.tenancyId() != null
                ? dispatch.tenancyId()
                : TenancyConstants.DEFAULT_TENANT_ID;

        // ── Resolve subjectId (Priority 1 > 2 > 3) ───────────────────────────────
        final UUID resolvedSubjectId;
        if (dispatch.subjectId() != null) {
            resolvedSubjectId = dispatch.subjectId();
        } else if (dispatch.correlationId() != null) {
            resolvedSubjectId = messageRepo
                    .findEarliestWithSubjectByCorrelationId(dispatch.correlationId(), tenancyId)
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
            resolvedCausedByEntryId = messageRepo.findByMessageId(dispatch.inReplyTo())
                    .map(e -> e.id)
                    .orElse(null);
        } else {
            resolvedCausedByEntryId = null;
        }

        final String resolvedActorId = actorIdProvider.resolve(dispatch.sender());

        final MessageLedgerEntry entry = new MessageLedgerEntry();
        entry.tenancyId = tenancyId;
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
        // entry.sequenceNumber assigned by ledger.save() via LedgerSequenceAllocator. Refs #256.
        entry.entryType = switch (dispatch.type()) {
            case QUERY, COMMAND, HANDOFF -> LedgerEntryType.COMMAND;
            default -> LedgerEntryType.EVENT;
        };

        if (dispatch.type() == MessageType.EVENT) {
            populateTelemetry(entry, dispatch.telemetry());
        } else {
            entry.content = dispatch.content();
        }

        // ── Attestation for terminal commitment types ─────────────────────────────
        // Guard: only attest against COMMAND or HANDOFF entries — not STATUS, EVENT, etc.
        // RESPONSE is included: when an agent sends RESPONSE on a COMMAND's corrId, the
        // commitment closes as FULFILLED but wrong vocabulary was used — write FLAGGED.
        // instanceof check is intentional: causedByEntryId may reference any LedgerEntry
        // subtype; attestation only makes sense for qhorus COMMAND/HANDOFF entries.
        if (ATTESTATION_TYPES.contains(dispatch.type()) && resolvedCausedByEntryId != null) {
            ledger.findEntryById(resolvedCausedByEntryId, tenancyId).ifPresent(prior -> {
                if (prior instanceof MessageLedgerEntry priorMsg
                        && ("COMMAND".equals(priorMsg.messageType) || "HANDOFF".equals(priorMsg.messageType))) {
                    final String capabilityTag = extractCapabilityTag(priorMsg.content);
                    final CommitmentContext ctx = new CommitmentContext(
                            priorMsg.correlationId, priorMsg.channelId, null, commitmentId, capabilityTag);
                    writeAttestation(resolvedSubjectId, priorMsg, dispatch.type(), resolvedActorId,
                            tenancyId, ctx);
                }
            });
        }

        ledger.save(entry, tenancyId);
        return new LedgerWriteOutcome(entry.id, resolvedSubjectId, resolvedCausedByEntryId);
    }

    private void writeAttestation(final UUID subjectId, final MessageLedgerEntry commandEntry,
            final MessageType terminalType, final String resolvedActorId, final String tenancyId,
            final CommitmentContext context) {
        attestationPolicy.attestationFor(terminalType, resolvedActorId, context).ifPresent(outcome -> {
            try {
                final LedgerAttestation attestation = new LedgerAttestation();
                attestation.ledgerEntryId = commandEntry.id;
                attestation.subjectId = subjectId;
                attestation.attestorId = outcome.attestorId();
                attestation.attestorType = outcome.attestorType();
                attestation.verdict = outcome.verdict();
                attestation.confidence = outcome.confidence();
                attestation.capabilityTag = context.capabilityTag();
                ledger.saveAttestation(attestation, tenancyId);
                LOG.debugf("LedgerAttestation %s written for COMMAND entry %s (correlationId='%s', capability='%s')",
                        attestation.verdict, commandEntry.id, commandEntry.correlationId, attestation.capabilityTag);
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
