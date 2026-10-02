package io.casehub.qhorus.api.store.query;

import java.time.Instant;

import io.casehub.qhorus.api.instance.Instance;

public final class InstanceQuery {

    private final String capability;
    private final String status;
    private final Instant staleOlderThan;
    private final String metadataKey;
    private final String metadataValue;

    private InstanceQuery(Builder b) {
        this.capability = b.capability;
        this.status = b.status;
        this.staleOlderThan = b.staleOlderThan;
        this.metadataKey = b.metadataKey;
        this.metadataValue = b.metadataValue;
    }

    public static InstanceQuery all() {
        return new Builder().build();
    }

    public static InstanceQuery online() {
        return new Builder().status("online").build();
    }

    public static InstanceQuery byCapability(String tag) {
        return new Builder().capability(tag).build();
    }

    public static InstanceQuery staleOlderThan(Instant threshold) {
        return new Builder().staleOlderThan(threshold).build();
    }

    public static InstanceQuery byMetadata(String key, String value) {
        return new Builder().metadataKey(key).metadataValue(value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String capability() {
        return capability;
    }

    public String status() {
        return status;
    }

    public Instant staleOlderThan() {
        return staleOlderThan;
    }

    public String metadataKey() {
        return metadataKey;
    }

    public String metadataValue() {
        return metadataValue;
    }

    public boolean matches(Instance inst) {
        if (status != null && !status.equals(inst.status())) {
            return false;
        }
        if (staleOlderThan != null && (inst.lastSeen() == null || !inst.lastSeen().isBefore(staleOlderThan))) {
            return false;
        }
        if (metadataKey != null) {
            if (inst.metadata() == null || !metadataValue.equals(inst.metadata().get(metadataKey))) {
                return false;
            }
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().capability(capability).status(status).staleOlderThan(staleOlderThan)
                            .metadataKey(metadataKey).metadataValue(metadataValue);
    }

    public static final class Builder {
        private String capability;
        private String status;
        private Instant staleOlderThan;
        private String metadataKey;
        private String metadataValue;

        public Builder capability(String v) {
            this.capability = v;
            return this;
        }

        public Builder status(String v) {
            this.status = v;
            return this;
        }

        public Builder staleOlderThan(Instant v) {
            this.staleOlderThan = v;
            return this;
        }

        public Builder metadataKey(String v) {
            this.metadataKey = v;
            return this;
        }

        public Builder metadataValue(String v) {
            this.metadataValue = v;
            return this;
        }

        public InstanceQuery build() {
            return new InstanceQuery(this);
        }
    }
}
