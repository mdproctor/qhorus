package io.casehub.qhorus.runtime.message;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.qhorus.api.message.MessageType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "Message")
@Table(name = "message")
@SequenceGenerator(name = "message_seq", sequenceName = "message_seq", allocationSize = 50)
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "message_seq")
    public Long id;

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Column(nullable = false)
    public String sender;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    public MessageType messageType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    public ActorType actorType;

    /* default = single-tenant sentinel; overridden by MessageService.dispatch() (Task 13); PP-20260520-e6a5f0 */
    @Column(name = "tenancy_id", nullable = false, updatable = false)
    public String tenancyId = "278776f9-e1b0-46fb-9032-8bddebdcf9ce"; // TenancyConstants.DEFAULT_TENANT_ID

    @Column(columnDefinition = "TEXT")
    public String content;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "payload")
    public String payload;

    @Column(name = "correlation_id")
    public String correlationId;

    @Column(name = "in_reply_to")
    public Long inReplyTo;

    @Column(name = "reply_count", nullable = false)
    public int replyCount = 0;

    @jakarta.persistence.Convert(converter = ArtefactRefListConverter.class)
    @Column(name = "artefact_refs", columnDefinition = "TEXT")
    public java.util.List<io.casehub.qhorus.api.message.ArtefactRef> artefactRefs;

    /** Addressing target: null (broadcast), instance:<id>, capability:<tag>, or role:<name>. */
    @Column(name = "target")
    public String target;
    @Column(name = "topic", length = 200)
    public String topic;


    /** Links to CommitmentStore entry. Auto-set by infrastructure on QUERY/COMMAND. */
    @Column(name = "commitment_id")
    public UUID commitmentId;

    /**
     * When the obligation must be discharged. Null = no temporal constraint (STATUS, RESPONSE, EVENT).
     * Set from channel config default on QUERY/COMMAND when not provided by sender.
     */
    @Column(name = "deadline")
    public Instant deadline;

    /** When the obligation was explicitly accepted. Null in v1; populated by v2 ACK mechanism. */
    @Column(name = "acknowledged_at")
    public Instant acknowledgedAt;

    @Column(name = "version", nullable = false)
    public int version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "corrects_message_id")
    public Long correctsMessageId;

    @Column(name = "retraction", nullable = false)
    public boolean retraction = false;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public static MessageEntity fromDomain(io.casehub.qhorus.api.message.Message msg) {
        MessageEntity e = new MessageEntity();
        e.id             = msg.id();
        e.channelId      = msg.channelId();
        e.sender         = msg.sender();
        e.messageType    = msg.messageType();
        e.actorType      = msg.actorType();
        e.tenancyId      = msg.tenancyId() != null ? msg.tenancyId() : TenancyConstants.DEFAULT_TENANT_ID;
        e.content        = msg.content();
        e.payload        = msg.payload();
        e.correlationId  = msg.correlationId();
        e.inReplyTo      = msg.inReplyTo();
        e.replyCount     = msg.replyCount();
        e.artefactRefs   = msg.artefactRefs();
        e.target         = msg.target();
        e.topic          = msg.topic();
        e.commitmentId   = msg.commitmentId();
        e.deadline       = msg.deadline();
        e.acknowledgedAt = msg.acknowledgedAt();
        e.version        = msg.version();
        e.createdAt      = msg.createdAt();
        e.correctsMessageId = msg.correctsMessageId();
        e.retraction     = msg.retraction();
        return e;}

    public io.casehub.qhorus.api.message.Message toDomain() {
        return new io.casehub.qhorus.api.message.Message(
                id, channelId, sender, messageType, actorType, tenancyId,
                content, payload, correlationId, inReplyTo, replyCount,
                artefactRefs, target, topic, commitmentId,
                deadline, acknowledgedAt, version, createdAt,
                correctsMessageId, retraction);}

}
