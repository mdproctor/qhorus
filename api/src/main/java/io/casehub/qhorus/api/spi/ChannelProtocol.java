package io.casehub.qhorus.api.spi;

import java.util.List;

public interface ChannelProtocol {

    String protocolName();

    List<String> evaluate(ProtocolContext context);
}
