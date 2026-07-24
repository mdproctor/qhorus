package io.casehub.qhorus.runtime.channel;

import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.Space;
import io.casehub.qhorus.api.channel.SpaceCreateRequest;
import io.casehub.qhorus.api.store.ChannelStore;
import io.casehub.qhorus.api.store.SpaceStore;
import io.casehub.qhorus.api.store.query.ChannelQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpaceServiceTest {

    private static final String TENANCY       = "default";
    private static final String OTHER_TENANCY = "other-tenant";

    private       SpaceService       service;
    private final Map<UUID, Space>   spaceMap   = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Channel> channelMap = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        spaceMap.clear();
        channelMap.clear();

        SpaceStore spaceStore = new SpaceStore() {
            @Override
            public Space put(Space s)            {
                                                     spaceMap.put(s.id(), s);
                                                     return s;
                                                 }

            @Override
            public Optional<Space> find(UUID id) {return Optional.ofNullable(spaceMap.get(id));}

            @Override
            public List<Space> findByName(String name) {
                return spaceMap.values().stream().filter(s -> name.equals(s.name())).toList();
            }

            @Override
            public List<Space> listByParent(UUID pid) {
                return spaceMap.values().stream().filter(s -> pid.equals(s.parentSpaceId())).toList();
            }

            @Override
            public List<Space> listRoots() {
                return spaceMap.values().stream()
                               .filter(s -> s.parentSpaceId() == null && s.tenancyId().equals(TENANCY)).toList();
            }

            @Override
            public boolean hasChildren(UUID sid) {
                return spaceMap.values().stream().anyMatch(s -> sid.equals(s.parentSpaceId()));
            }

            @Override
            public void delete(UUID id) {spaceMap.remove(id);}
        };

        ChannelStore channelStore = new ChannelStore() {
            @Override
            public Channel put(Channel ch)         {
                                                       channelMap.put(ch.id(), ch);
                                                       return ch;
                                                   }

            @Override
            public Optional<Channel> find(UUID id) {return Optional.ofNullable(channelMap.get(id));}

            @Override
            public Optional<Channel> findByName(String name) {
                return channelMap.values().stream().filter(c -> c.name().equals(name)).findFirst();
            }

            @Override
            public List<Channel> scan(ChannelQuery q) {
                return channelMap.values().stream().filter(q::matches).toList();
            }

            @Override
            public void delete(UUID id)                          {channelMap.remove(id);}

            @Override
            public void updateLastActivity(UUID cid, String tid) {}

            @Override
            public void updateTrackDelivery(UUID cid, Boolean td) {}

            @Override
            public boolean hasChannelsInSpace(UUID spaceId) {
                if (spaceId == null) {return false;}
                return channelMap.values().stream().anyMatch(c -> spaceId.equals(c.spaceId()));
            }
        };

        service                  = new SpaceService();
        service.spaceStore       = spaceStore;
        service.channelStore     = channelStore;
        service.currentPrincipal = new CurrentPrincipal() {
            @Override
            public String tenancyId()             {return TENANCY;}

            @Override
            public String actorId()               {return "test";}

            @Override
            public java.util.Set<String> groups() {return java.util.Set.of();}

            @Override
            public boolean isCrossTenantAdmin()   {return false;}
        };
    }

    private Space createSpace(String name) {
        return service.create(new SpaceCreateRequest(name, null, null));
    }

    private Space createSpace(String name, UUID parentId) {
        return service.create(new SpaceCreateRequest(name, null, parentId));
    }

    private Channel putChannel(String name, UUID spaceId, String tenancyId) {
        Channel ch = Channel.builder(name).id(UUID.randomUUID())
                            .semantic(ChannelSemantic.APPEND).spaceId(spaceId)
                            .tenancyId(tenancyId).createdAt(Instant.now()).lastActivityAt(Instant.now()).build();
        channelMap.put(ch.id(), ch);
        return ch;
    }

    // --- create ---

    @Test
    void create_rootSpace() {
        Space space = service.create(new SpaceCreateRequest("project-alpha", "Alpha project", null));
        assertThat(space.id()).isNotNull();
        assertThat(space.name()).isEqualTo("project-alpha");
        assertThat(space.description()).isEqualTo("Alpha project");
        assertThat(space.parentSpaceId()).isNull();
        assertThat(space.tenancyId()).isEqualTo(TENANCY);
    }

    @Test
    void create_childSpace() {
        Space parent = createSpace("org-acme");
        Space child  = service.create(new SpaceCreateRequest("team-eng", "Engineering", parent.id()));
        assertThat(child.parentSpaceId()).isEqualTo(parent.id());
    }

    @Test
    void create_withNonExistentParent_throws() {
        UUID bogus = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new SpaceCreateRequest("orphan", null, bogus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Parent space not found");
    }

    @Test
    void create_exceedingMaxDepth_throws() {
        Space current = createSpace("level-0");
        for (int i = 1; i < SpaceService.MAX_DEPTH; i++) {
            current = createSpace("level-" + i, current.id());
        }
        Space deepest = current;
        assertThatThrownBy(() -> createSpace("too-deep", deepest.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maximum nesting depth");
    }

    // --- findById ---

    @Test
    void findById_existing() {
        Space created = createSpace("s1");
        assertThat(service.findById(created.id())).isPresent().get()
                                                  .extracting(Space::id).isEqualTo(created.id());
    }

    @Test
    void findById_nonExistent_returnsEmpty() {
        assertThat(service.findById(UUID.randomUUID())).isEmpty();
    }

    // --- findByName ---

    @Test
    void findByName_singleMatch_returnsSpace() {
        Space created = createSpace("unique-name");
        assertThat(service.findByName("unique-name")).isPresent().get()
                                                     .extracting(Space::id).isEqualTo(created.id());
    }

    @Test
    void findByName_noMatch_returnsEmpty() {
        assertThat(service.findByName("nonexistent")).isEmpty();
    }

    @Test
    void findByName_multipleMatches_throws() {
        Space parent = createSpace("parent");
        createSpace("dup-name");
        createSpace("dup-name", parent.id());
        assertThatThrownBy(() -> service.findByName("dup-name"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous");
    }

    // --- list ---

    @Test
    void listRoots_returnsOnlyRoots() {
        Space root1 = createSpace("r1");
        Space root2 = createSpace("r2");
        createSpace("c1", root1.id());
        assertThat(service.listRoots()).extracting(Space::id)
                                       .containsExactlyInAnyOrder(root1.id(), root2.id());
    }

    @Test
    void listChildren_returnsDirectChildren() {
        Space root   = createSpace("root");
        Space child1 = createSpace("child1", root.id());
        Space child2 = createSpace("child2", root.id());
        createSpace("gc", child1.id());
        assertThat(service.listChildren(root.id())).extracting(Space::id)
                                                   .containsExactlyInAnyOrder(child1.id(), child2.id());
    }

    @Test
    void listChannels_returnsBySpaceId() {
        Space   space = createSpace("s1");
        Channel ch1   = putChannel("ch-a", space.id(), TENANCY);
        Channel ch2   = putChannel("ch-b", space.id(), TENANCY);
        putChannel("other", null, TENANCY);
        assertThat(service.listChannels(space.id())).extracting(Channel::name)
                                                    .containsExactlyInAnyOrder("ch-a", "ch-b");
    }

    // --- delete ---

    @Test
    void delete_emptySpace_succeeds() {
        Space space = createSpace("s1");
        service.delete(space.id());
        assertThat(service.findById(space.id())).isEmpty();
    }

    @Test
    void delete_withChildSpaces_throws() {
        Space parent = createSpace("parent");
        createSpace("child", parent.id());
        assertThatThrownBy(() -> service.delete(parent.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("child spaces");
    }

    @Test
    void delete_withChannels_throws() {
        Space space = createSpace("s1");
        putChannel("ch-in-space", space.id(), TENANCY);
        assertThatThrownBy(() -> service.delete(space.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channels");
    }

    @Test
    void delete_nonExistent_throws() {
        assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Space not found");
    }

    // --- rename ---

    @Test
    void rename_updatesName() {
        Space space   = createSpace("old-name");
        Space renamed = service.rename(space.id(), "new-name");
        assertThat(renamed.name()).isEqualTo("new-name");
        assertThat(service.findById(space.id()).get().name()).isEqualTo("new-name");
    }

    @Test
    void rename_blankName_throws() {
        Space space = createSpace("s1");
        assertThatThrownBy(() -> service.rename(space.id(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    // --- updateDescription ---

    @Test
    void updateDescription_updatesDescription() {
        Space space   = service.create(new SpaceCreateRequest("s1", "old desc", null));
        Space updated = service.updateDescription(space.id(), "new desc");
        assertThat(updated.description()).isEqualTo("new desc");
    }

    @Test
    void updateDescription_null_clearsDescription() {
        Space space   = service.create(new SpaceCreateRequest("s1", "old desc", null));
        Space updated = service.updateDescription(space.id(), null);
        assertThat(updated.description()).isNull();
    }

    // --- moveSpace ---

    @Test
    void moveSpace_validReparent() {
        Space a     = createSpace("a");
        Space b     = createSpace("b");
        Space moved = service.moveSpace(a.id(), b.id());
        assertThat(moved.parentSpaceId()).isEqualTo(b.id());
    }

    @Test
    void moveSpace_toRoot() {
        Space parent = createSpace("parent");
        Space child  = createSpace("child", parent.id());
        Space moved  = service.moveSpace(child.id(), null);
        assertThat(moved.parentSpaceId()).isNull();
    }

    @Test
    void moveSpace_cycleDetection_throws() {
        Space a = createSpace("a");
        Space b = createSpace("b", a.id());
        assertThatThrownBy(() -> service.moveSpace(a.id(), b.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void moveSpace_selfMove_throws() {
        Space a = createSpace("a");
        assertThatThrownBy(() -> service.moveSpace(a.id(), a.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void moveSpace_depthExceeded_throws() {
        Space chain = createSpace("root");
        for (int i = 1; i < SpaceService.MAX_DEPTH; i++) {
            chain = createSpace("level-" + i, chain.id());
        }
        Space deep = createSpace("deep");
        createSpace("deep-child", deep.id());
        Space target = chain;
        assertThatThrownBy(() -> service.moveSpace(deep.id(), target.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("depth");}

    @Test
    void moveSpace_nonExistentParent_throws() {
        Space a     = createSpace("a");
        UUID  bogus = UUID.randomUUID();
        assertThatThrownBy(() -> service.moveSpace(a.id(), bogus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target parent space not found");
    }

    // --- moveChannelToSpace ---

    @Test
    void moveChannelToSpace_assignsSpace() {
        Space   space = createSpace("s1");
        Channel ch    = putChannel("ch1", null, TENANCY);
        Channel moved = service.moveChannelToSpace(ch.id(), space.id());
        assertThat(moved.spaceId()).isEqualTo(space.id());
    }

    @Test
    void moveChannelToSpace_nullRemovesSpace() {
        Space   space = createSpace("s1");
        Channel ch    = putChannel("ch1", space.id(), TENANCY);
        Channel moved = service.moveChannelToSpace(ch.id(), null);
        assertThat(moved.spaceId()).isNull();
    }

    @Test
    void moveChannelToSpace_crossTenancy_throws() {
        Space   space = createSpace("s1");
        Channel ch    = putChannel("ch1", null, OTHER_TENANCY);
        assertThatThrownBy(() -> service.moveChannelToSpace(ch.id(), space.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenancy");
    }

    @Test
    void moveChannelToSpace_nonExistentSpace_throws() {
        Channel ch    = putChannel("ch1", null, TENANCY);
        UUID    bogus = UUID.randomUUID();
        assertThatThrownBy(() -> service.moveChannelToSpace(ch.id(), bogus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Space not found");
    }

    @Test
    void moveChannelToSpace_nonExistentChannel_throws() {
        Space space = createSpace("s1");
        UUID  bogus = UUID.randomUUID();
        assertThatThrownBy(() -> service.moveChannelToSpace(bogus, space.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    // --- recursive nesting ---

    @Test
    void recursiveNesting_threeLevels() {
        Space org     = createSpace("org");
        Space team    = createSpace("team", org.id());
        Space project = createSpace("project", team.id());
        assertThat(service.listRoots()).hasSize(1);
        assertThat(service.listChildren(org.id())).hasSize(1);
        assertThat(service.listChildren(team.id())).hasSize(1);
        assertThat(service.listChildren(project.id())).isEmpty();
    }
}
