package io.casehub.qhorus.runtime.gateway;

public class DuplicateParticipatingBackendException extends IllegalStateException {
    public DuplicateParticipatingBackendException(String channelId, String existingBackendId) {
        super("Channel " + channelId + " already has a HumanParticipatingChannelBackend: "
                + existingBackendId
                + ". At most one participatory human backend is allowed per channel.");
    }
}
