package io.casehub.qhorus.runtime.store.jpa;

import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.store.ReactiveMessageStore;
import io.casehub.qhorus.api.store.query.MessageQuery;
import io.casehub.qhorus.runtime.message.MessageEntity;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveJpaMessageStore implements ReactiveMessageStore {

    @Inject
    MessageReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<Message> put(Message message) {
        MessageEntity entity = MessageEntity.fromDomain(message);
        return repo.persist(entity).map(MessageEntity::toDomain);
    }

    @Override
    public Uni<Optional<Message>> find(Long id) {
        return repo.findById(id)
                .map(e -> Optional.ofNullable(e).map(MessageEntity::toDomain));
    }

    @Override
    public Uni<List<Message>> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        String jpql = "FROM Message WHERE " + mq.where() + " ORDER BY id ASC";

        return repo.<MessageEntity>list(jpql, mq.params())
                .map(results -> {
                    List<MessageEntity> limited = q.limit() != null && results.size() > q.limit()
                            ? results.subList(0, q.limit())
                            : results;
                    return limited.stream().map(MessageEntity::toDomain).toList();
                });
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteAll(UUID channelId) {
        return repo.delete("channelId", channelId).replaceWithVoid();
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteNonEvent(UUID channelId) {
        return repo.delete("channelId = ?1 AND messageType != ?2",
                channelId, io.casehub.qhorus.api.message.MessageType.EVENT).replaceWithVoid();
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(Long id) {
        return repo.deleteById(id).replaceWithVoid();
    }

    @Override
    public Uni<Integer> countByChannel(UUID channelId) {
        return repo.count("channelId", channelId).map(Long::intValue);
    }

    @Override
    public Uni<Long> count(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        return repo.count(mq.where(), mq.params());
    }

    @Override
    public Uni<Map<UUID, Long>> countAllByChannel() {
        return repo.getSession()
                .flatMap(session -> session
                        .createQuery(
                                "SELECT m.channelId, COUNT(m) FROM Message m GROUP BY m.channelId",
                                Object[].class)
                        .getResultList())
                .map(rows -> rows.stream()
                        .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1])));
    }

    @Override
    public Uni<List<String>> distinctSendersByChannel(UUID channelId, MessageType excludedType) {
        return repo.<MessageEntity>list("channelId = ?1 AND messageType != ?2", channelId, excludedType)
                .map(msgs -> msgs.stream()
                        .map(m -> m.sender)
                        .filter(s -> s != null && !s.isBlank())
                        .distinct()
                        .sorted()
                        .toList());
    }

    @Override
    public Uni<Optional<Message>> findLastMessage(UUID channelId) {
        return repo.find("channelId = ?1 ORDER BY id DESC", channelId)
                .firstResult()
                .map(e -> Optional.ofNullable(e).map(MessageEntity::toDomain));
    }

    /**
     * Streams messages matching {@code query} as a {@link Multi}.
     *
     * <p><strong>Current limitation:</strong> Quarkus 3.32 Hibernate Reactive Panache exposes
     * only {@code PanacheQuery.list()} (materialises a full {@code List<Message>} before
     * emitting). A cursor-backed scrollable result API is not available in the Panache public
     * surface at this version. This implementation wraps the materialised list as a
     * {@code Multi} — memory profile is identical to {@link #scan}, but the interface is the
     * right shape. When Hibernate Reactive Panache exposes cursor streaming, replace with
     * {@code PanacheQuery.stream()} or {@code ReactiveSession.scroll()}.
     *
     * <p>Messages are emitted in ascending insertion order ({@code id ASC}).
     */
    @Override
    public Multi<Message> stream(final MessageQuery q) {
        return scan(q).toMulti().flatMap(list -> Multi.createFrom().iterable(list));
    }

    @Override
    public Uni<Integer> updateTopicName(UUID channelId, String oldTopic, String newTopic) {
        return repo.getSession().flatMap(session ->
                session.createMutationQuery(
                        "UPDATE Message SET topic = :newTopic WHERE channelId = :channelId AND LOWER(topic) = LOWER(:oldTopic)")
                        .setParameter("newTopic", newTopic)
                        .setParameter("channelId", channelId)
                        .setParameter("oldTopic", oldTopic)
                        .executeUpdate());
    }

    @Override
    public Uni<Integer> updateChannelId(UUID sourceChannelId, String topic, UUID targetChannelId) {
        return repo.getSession().flatMap(session ->
                                                 session.createMutationQuery(
                                                                "UPDATE Message SET channelId = :target WHERE channelId = :source AND LOWER(topic) = LOWER(:topic)")
                                                        .setParameter("target", targetChannelId)
                                                        .setParameter("source", sourceChannelId)
                                                        .setParameter("topic", topic)
                                                        .executeUpdate());
    }

    @Override
    public Uni<List<io.casehub.qhorus.api.message.MessageView>> findRecentAsync(UUID channelId, int limit) {
        return repo.getSession().flatMap(session ->
                                                 session.createQuery(
                                                                "FROM Message WHERE channelId = ?1 AND messageType != ?2 ORDER BY id DESC",
                                                                io.casehub.qhorus.runtime.message.MessageEntity.class)
                                                        .setParameter(1, channelId)
                                                        .setParameter(2, MessageType.EVENT)
                                                        .setMaxResults(limit)
                                                        .getResultList()
                                                        .map(entities -> {
                                                            java.util.ArrayList<io.casehub.qhorus.api.message.MessageView> views = new java.util.ArrayList<>(entities.size());
                                                            for (int i = entities.size() - 1; i >= 0; i--) {
                                                                io.casehub.qhorus.api.message.Message m = entities.get(i).toDomain();
                                                                views.add(new io.casehub.qhorus.api.message.MessageView(
                                                                        m.id(), m.channelId(), m.sender(), m.messageType(), m.content(),
                                                                        m.correlationId(), m.inReplyTo(), m.target(), m.topic(),
                                                                        m.artefactRefs(), m.actorType(), m.createdAt(), m.deadline(), m.replyCount()));
                                                            }
                                                            return (List<io.casehub.qhorus.api.message.MessageView>) views;
                                                        }));
    }


}
