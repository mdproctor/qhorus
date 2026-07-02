package io.casehub.qhorus.api.store.query;

import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.Channel;

public final class ChannelQuery {

    private final String namePattern;
    private final String namePrefix;
    private final String keyword;
    private final ChannelSemantic semantic;
    private final Boolean paused;

    private ChannelQuery(Builder b) {
        this.namePattern = b.namePattern;
        this.namePrefix = b.namePrefix;
        this.keyword = b.keyword;
        this.semantic = b.semantic;
        this.paused = b.paused;
    }

    public static ChannelQuery all() {
        return new Builder().build();
    }

    public static ChannelQuery pausedOnly() {
        return new Builder().paused(true).build();
    }

    public static ChannelQuery byName(String pattern) {
        return new Builder().namePattern(pattern).build();
    }

    public static ChannelQuery byKeyword(String keyword) {
        return new Builder().keyword(keyword).build();
    }

    public static ChannelQuery byNamePrefix(String prefix) {
        return new Builder().namePrefix(prefix).build();
    }

    public static ChannelQuery bySemantic(ChannelSemantic s) {
        return new Builder().semantic(s).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String namePattern() {
        return namePattern;
    }

    public String namePrefix() {
        return namePrefix;
    }

    public String keyword() {
        return keyword;
    }

    public ChannelSemantic semantic() {
        return semantic;
    }

    public Boolean paused() {
        return paused;
    }

    public boolean matches(Channel ch) {
        if (paused != null && paused != ch.paused()) {
            return false;
        }
        if (semantic != null && !semantic.equals(ch.semantic())) {
            return false;
        }
        if (namePattern != null && (ch.name() == null || !ch.name().matches(namePattern.replace("*", ".*")))) {
            return false;
        }
        if (namePrefix != null && (ch.name() == null || !ch.name().startsWith(namePrefix))) {
            return false;
        }
        if (keyword != null) {
            String lower = keyword.toLowerCase();
            boolean nameMatch = ch.name() != null && ch.name().toLowerCase().contains(lower);
            boolean descMatch = ch.description() != null && ch.description().toLowerCase().contains(lower);
            if (!nameMatch && !descMatch) return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().namePattern(namePattern).namePrefix(namePrefix).keyword(keyword).semantic(semantic).paused(paused);
    }

    public static final class Builder {
        private String namePattern;
        private String namePrefix;
        private String keyword;
        private ChannelSemantic semantic;
        private Boolean paused;

        public Builder namePattern(String v) {
            this.namePattern = v;
            return this;
        }

        public Builder namePrefix(String v) {
            this.namePrefix = v;
            return this;
        }

        public Builder keyword(String v) {
            this.keyword = v;
            return this;
        }

        public Builder semantic(ChannelSemantic v) {
            this.semantic = v;
            return this;
        }

        public Builder paused(Boolean v) {
            this.paused = v;
            return this;
        }

        public ChannelQuery build() {
            return new ChannelQuery(this);
        }
    }
}
