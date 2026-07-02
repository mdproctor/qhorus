package io.casehub.qhorus.service;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestTransaction
class ChannelServiceTest extends ChannelServiceContractTest {

    @Inject
    ChannelService svc;

    @Override
    protected Channel create(String name, String desc, ChannelSemantic sem) {
        return svc.create(ChannelCreateRequest.builder(name)
                .description(desc).semantic(sem).build());
    }

    @Override
    protected Optional<Channel> findByName(String name) {
        return svc.findByName(name);
    }

    @Override
    protected List<Channel> listAll() {
        return svc.listAll();
    }

    @Override
    protected Channel pause(String name) {
        return svc.pause(svc.findByName(name).orElseThrow().id());
    }

    @Override
    protected Channel resume(String name) {
        return svc.resume(svc.findByName(name).orElseThrow().id());
    }
}
