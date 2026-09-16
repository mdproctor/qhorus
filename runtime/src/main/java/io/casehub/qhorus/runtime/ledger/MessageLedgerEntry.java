package io.casehub.qhorus.runtime.ledger;

import io.casehub.ledger.runtime.model.jpa.JpaLedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A ledger entry recording any agent-to-agent message as a speech act.
 *
 * <p>
 * Every message type is recorded — this is the complete, immutable channel audit trail.
 * {@link #messageType} discriminates content interpretation. Telemetry fields
 * ({@code toolName}, {@code durationMs}, etc.) are populated only for EVENT messages.
 *
 * <p>
 * The CommitmentStore is the live obligation state; this ledger is the permanent record.
 * {@code causedByEntryId} (inherited from {@link JpaLedgerEntry})
 * links terminal messages back to the COMMAND.
 *
 * <p>
 * Refs #100, Epic #99.
 */
@Entity
@Table(name = "message_ledger_entry")
@DiscriminatorValue("QHORUS_MESSAGE")
public class MessageLedgerEntry extends JpaLedgerEntry {

    /** UUID of the channel this message was sent on. Mirrors {@code subjectId}. */
    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(name = "message_id", nullable = false)
    public Long messageId;

    /**
     * Qhorus {@code MessageType} enum name — the discriminator for interpreting all other fields.
     * Normative types (COMMAND, DECLINE, DONE, etc.) populate {@link #content} and {@link #target}.
     * {@code EVENT} populates the telemetry fields ({@link #toolName}, {@link #durationMs}, etc.).
     */
    @Column(name = "message_type", nullable = false)
    public String messageType;

    /** Intended recipient for COMMAND and HANDOFF. Null for broadcasts. */
    @Column(name = "target")
    public String target;

    /**
     * Message content for normative types: COMMAND description, DECLINE/FAILURE reason,
     * DONE summary. Null for EVENT (telemetry uses dedicated fields).
     */
    @Column(name = "content", columnDefinition = "TEXT")
    public String content;

    /** Propagated from the message — used for causal chain resolution and request/reply tracing. */
    @Column(name = "correlation_id")
    public String correlationId;

    /** Links this entry to the live CommitmentStore record for obligation-bearing message types. */
    @Column(name = "commitment_id")
    public UUID commitmentId;

    @Column(name = "topic")
    public String topic;

    // EVENT-only telemetry fields — null for all non-EVENT message types

    @Column(name = "tool_name")
    public String toolName;

    @Column(name = "duration_ms")
    public Long durationMs;

    @Column(name = "token_count")
    public Long tokenCount;

    @Column(name = "context_refs", columnDefinition = "TEXT")
    public String contextRefs;

    @Column(name = "source_entity", columnDefinition = "TEXT")
    public String sourceEntity;
    @Column(name = "context_window_pct")
    public Integer contextWindowPct;

    @Column(name = "routing_original_target")
    public String routingOriginalTarget;
    @Column(name = "routing_selected_agent")
    public String routingSelectedAgent;
    @Column(name = "routing_strategy")
    public String routingStrategy;
    @Column(name = "routing_candidate_count")
    public Integer routingCandidateCount;
    @Column(name = "corrects_message_id")
    public Long correctsMessageId;

    @Column(name = "judgment_id")
    public UUID    judgmentId;

    @Column(name = "judgment_type", length = 100)
    public String judgmentType;

    @Column(name = "verification_outcome", length = 20)
    public String verificationOutcome;

    @Column(name = "evidence_quality")
    public Double evidenceQuality;
    @Column(name = "reasoning", columnDefinition = "TEXT")
    public String reasoning;


    @Override
    protected byte[] domainContentBytes() {
        String canonical = String.join("|",
                                       channelId != null ? channelId.toString() : "",
                                       messageId != null ? messageId.toString() : "",
                                       messageType != null ? messageType : "",
                                       target != null ? target : "",
                                       content != null ? content : "",
                                       correlationId != null ? correlationId : "",
                                       commitmentId != null ? commitmentId.toString() : "",
                                       topic != null ? topic : "",
                                       toolName != null ? toolName : "",
                                       durationMs != null ? durationMs.toString() : "",
                                       tokenCount != null ? tokenCount.toString() : "",
                                       contextRefs != null ? contextRefs : "",
                                       sourceEntity != null ? sourceEntity : "",
                                       contextWindowPct != null ? contextWindowPct.toString() : ""
                                      );
        if (judgmentId != null || judgmentType != null
            || verificationOutcome != null || evidenceQuality != null || reasoning != null) {
            canonical += "|J:"
                         + (judgmentId != null ? judgmentId.toString() : "") + "|"
                         + (judgmentType != null ? judgmentType : "") + "|"
                         + (verificationOutcome != null ? verificationOutcome : "") + "|"
                         + (evidenceQuality != null ? String.valueOf(evidenceQuality) : "") + "|"
                         + (reasoning != null ? reasoning : "");
        }
        return canonical.getBytes(StandardCharsets.UTF_8);
    }
}
