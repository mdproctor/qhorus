package io.casehub.qhorus.runtime.store.jpa;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.ReactiveMessageStore;
import io.casehub.qhorus.runtime.store.query.MessageQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveJpaMessageStore implements ReactiveMessageStore {

    @Inject
    MessageReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<Message> put(Message message) {
        return repo.persist(message);
    }

    @Override
    public Uni<Optional<Message>> find(Long id) {
        return repo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<List<Message>> scan(MessageQuery q) {
        MessageQueryJpql mq = MessageQueryJpql.from(q);
        String jpql = "FROM Message WHERE " + mq.where() + " ORDER BY id ASC";

        return repo.list(jpql, mq.params())
                .map(results -> q.limit() != null && results.size() > q.limit()
                        ? results.subList(0, q.limit())
                        : results);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteAll(UUID channelId) {
        return repo.delete("channelId", channelId).replaceWithVoid();
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
        return repo.list("channelId = ?1 AND messageType != ?2", channelId, excludedType)
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
                .map(Optional::ofNullable);
    }
}
