package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.store.query.MessageQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds reusable JPQL WHERE predicates for {@link MessageQuery}.
 * Shared by {@link JpaMessageStore} and {@link ReactiveJpaMessageStore}
 * so scan() and count() stay in sync when MessageQuery gains new fields.
 */
record MessageQueryJpql(String where, Object[] params) {

    /** Tenant-unaware factory — used by cross-tenant stores. */
    static MessageQueryJpql from(MessageQuery q) {
        StringBuilder where  = new StringBuilder("1=1");
        List<Object>  params = new ArrayList<>();
        int           idx    = 1;

        if (q.channelId() != null) {
            where.append(" AND channelId = ?").append(idx++);
            params.add(q.channelId());
        }
        if (q.afterId() != null) {
            if (q.afterVersion() != null) {
                where.append(" AND (id > ?").append(idx++);
                params.add(q.afterId());
                where.append(" OR (id = ?").append(idx++);
                params.add(q.afterId());
                where.append(" AND version > ?").append(idx++).append("))");
                params.add(q.afterVersion());
            } else {
                where.append(" AND id > ?").append(idx++);
                params.add(q.afterId());
            }
        }
        if (q.sender() != null) {
            where.append(" AND sender = ?").append(idx++);
            params.add(q.sender());
        }
        if (q.target() != null) {
            where.append(" AND target = ?").append(idx++);
            params.add(q.target());
        }
        if (q.inReplyTo() != null) {
            where.append(" AND inReplyTo = ?").append(idx++);
            params.add(q.inReplyTo());
        }
        if (q.messageType() != null) {
            where.append(" AND messageType = ?").append(idx++);
            params.add(q.messageType());
        }
        if (q.correlationId() != null) {
            where.append(" AND correlationId = ?").append(idx++);
            params.add(q.correlationId());
        }
        if (q.excludeTypes() != null && !q.excludeTypes().isEmpty()) {
            where.append(" AND messageType NOT IN ?").append(idx++);
            params.add(q.excludeTypes());
        }
        if (q.contentPattern() != null) {
            where.append(" AND LOWER(content) LIKE ?").append(idx++);
            params.add("%" + q.contentPattern().toLowerCase() + "%");
        }
        if (q.topic() != null) {
            where.append(" AND LOWER(topic) = LOWER(?").append(idx++).append(")");
            params.add(q.topic());
        }

        return new MessageQueryJpql(where.toString(), params.toArray());}

    /** Tenant-scoped factory — used by {@link JpaMessageStore}. */
    static MessageQueryJpql from(MessageQuery q, String tenancyId) {
        StringBuilder where  = new StringBuilder("tenancyId = ?1");
        List<Object>  params = new ArrayList<>();
        params.add(tenancyId);
        int idx = 2;

        if (q.channelId() != null) {
            where.append(" AND channelId = ?").append(idx++);
            params.add(q.channelId());
        }
        if (q.afterId() != null) {
            if (q.afterVersion() != null) {
                where.append(" AND (id > ?").append(idx++);
                params.add(q.afterId());
                where.append(" OR (id = ?").append(idx++);
                params.add(q.afterId());
                where.append(" AND version > ?").append(idx++).append("))");
                params.add(q.afterVersion());
            } else {
                where.append(" AND id > ?").append(idx++);
                params.add(q.afterId());
            }
        }
        if (q.sender() != null) {
            where.append(" AND sender = ?").append(idx++);
            params.add(q.sender());
        }
        if (q.target() != null) {
            where.append(" AND target = ?").append(idx++);
            params.add(q.target());
        }
        if (q.inReplyTo() != null) {
            where.append(" AND inReplyTo = ?").append(idx++);
            params.add(q.inReplyTo());
        }
        if (q.messageType() != null) {
            where.append(" AND messageType = ?").append(idx++);
            params.add(q.messageType());
        }
        if (q.correlationId() != null) {
            where.append(" AND correlationId = ?").append(idx++);
            params.add(q.correlationId());
        }
        if (q.excludeTypes() != null && !q.excludeTypes().isEmpty()) {
            where.append(" AND messageType NOT IN ?").append(idx++);
            params.add(q.excludeTypes());
        }
        if (q.contentPattern() != null) {
            where.append(" AND LOWER(content) LIKE ?").append(idx++);
            params.add("%" + q.contentPattern().toLowerCase() + "%");
        }
        if (q.topic() != null) {
            where.append(" AND LOWER(topic) = LOWER(?").append(idx++).append(")");
            params.add(q.topic());
        }

        return new MessageQueryJpql(where.toString(), params.toArray());}
}
