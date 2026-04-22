package io.quarkiverse.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import io.quarkiverse.ledger.runtime.model.LedgerEntry;

/**
 * A ledger entry scoped to a single agent-to-agent EVENT message.
 *
 * <p>
 * Extends the domain-agnostic {@link LedgerEntry} base class using JPA JOINED
 * inheritance. The {@code agent_message_ledger_entry} table holds Qhorus-specific
 * telemetry fields; all common audit fields live in {@code ledger_entry}. The
 * {@code subjectId} field on the base class carries the Channel UUID.
 *
 * <p>
 * Only EVENT-type messages produce ledger entries. The mandatory {@code toolName}
 * and {@code durationMs} fields encode the minimal telemetry payload; all other
 * fields are optional enrichment.
 *
 * <p>
 * Refs #51, Epic #50.
 */
@Entity
@Table(name = "agent_message_ledger_entry")
@DiscriminatorValue("AGENT_MESSAGE")
public class AgentMessageLedgerEntry extends LedgerEntry {

    /** UUID of the channel the EVENT was posted to. Mirrors {@code subjectId}. */
    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    /** Primary key of the {@code Message} row that triggered this entry. */
    @Column(name = "message_id", nullable = false)
    public Long messageId;

    /** Name of the agent tool that was invoked — e.g. {@code "read_file"}. */
    @Column(name = "tool_name", nullable = false)
    public String toolName;

    /** Wall-clock duration of the tool invocation in milliseconds. */
    @Column(name = "duration_ms", nullable = false)
    public Long durationMs;

    /** Optional LLM token count consumed by the invocation. */
    @Column(name = "token_count")
    public Long tokenCount;

    /** Optional JSON array or string listing context references used. */
    @Column(name = "context_refs", columnDefinition = "TEXT")
    public String contextRefs;

    /** Optional JSON object describing the source domain entity that triggered the event. */
    @Column(name = "source_entity", columnDefinition = "TEXT")
    public String sourceEntity;

    /** Optional correlation ID from the originating {@code Message} — for request/reply tracing. */
    @Column(name = "correlation_id")
    public String correlationId;
}
