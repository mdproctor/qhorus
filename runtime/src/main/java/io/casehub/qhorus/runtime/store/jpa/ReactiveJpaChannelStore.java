package io.casehub.qhorus.runtime.store.jpa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.ReactiveChannelStore;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@IfBuildProperty(name = "casehub.qhorus.reactive.enabled", stringValue = "true")
@ApplicationScoped
public class ReactiveJpaChannelStore implements ReactiveChannelStore {

    @Inject
    ChannelReactivePanacheRepo repo;

    @Override
    @WithTransaction
    public Uni<Channel> put(Channel channel) {
        return repo.persist(channel);
    }

    @Override
    public Uni<Optional<Channel>> find(UUID id) {
        return repo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<Channel>> findByName(String name) {
        return repo.find("name", name).firstResult().map(Optional::ofNullable);
    }

    @Override
    public Uni<List<Channel>> scan(ChannelQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Channel WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.paused() != null) {
            jpql.append(" AND paused = ?").append(idx++);
            params.add(q.paused());
        }
        if (q.semantic() != null) {
            jpql.append(" AND semantic = ?").append(idx++);
            params.add(q.semantic());
        }
        if (q.namePattern() != null) {
            jpql.append(" AND name LIKE ?").append(idx++);
            params.add(q.namePattern().replace("*", "%"));
        }
        if (q.namePrefix() != null) {
            jpql.append(" AND name LIKE ?").append(idx++).append(" ESCAPE '!'");
            params.add(escapeLikePrefix(q.namePrefix()) + "%");
        }

        return repo.list(jpql.toString(), params.toArray());
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return repo.deleteById(id).replaceWithVoid();
    }

    @Override
    public Uni<Void> updateLastActivity(UUID channelId) {
        return repo.update("lastActivityAt = ?1 WHERE id = ?2", Instant.now(), channelId)
                .replaceWithVoid();
    }

    private static String escapeLikePrefix(String prefix) {
        return prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
