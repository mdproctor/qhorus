package io.casehub.qhorus.api.store.query;

import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelSemantic;

import java.util.UUID;

public final class ChannelQuery {

    private final String          namePattern;
    private final String          namePrefix;
    private final String          keyword;
    private final ChannelSemantic semantic;
    private final Boolean         paused;
    private final UUID            spaceId;
    private final boolean         topLevelOnly;
    private final String          metadataKey;
    private final String          metadataValue;

    private ChannelQuery(Builder b) {
        this.namePattern  = b.namePattern;
        this.namePrefix   = b.namePrefix;
        this.keyword      = b.keyword;
        this.semantic     = b.semantic;
        this.paused       = b.paused;
        this.spaceId      = b.spaceId;
        this.topLevelOnly = b.topLevelOnly;
        this.metadataKey  = b.metadataKey;
        this.metadataValue = b.metadataValue;
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

    public static ChannelQuery bySpaceId(UUID spaceId) {
        return new Builder().spaceId(spaceId).build();
    }

    public static ChannelQuery topLevel() {
        return new Builder().topLevelOnly(true).build();
    }

    public static ChannelQuery byMetadata(String key, String value) {
        return new Builder().metadataKey(key).metadataValue(value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String namePattern()       {return namePattern;}

    public String namePrefix()        {return namePrefix;}

    public String keyword()           {return keyword;}

    public ChannelSemantic semantic() {return semantic;}

    public Boolean paused()           {return paused;}

    public UUID spaceId()             {return spaceId;}

    public boolean topLevelOnly()     {return topLevelOnly;}

    public String metadataKey()       {return metadataKey;}

    public String metadataValue()     {return metadataValue;}

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
            String  lower     = keyword.toLowerCase();
            boolean nameMatch = ch.name() != null && ch.name().toLowerCase().contains(lower);
            boolean descMatch = ch.description() != null && ch.description().toLowerCase().contains(lower);
            if (!nameMatch && !descMatch) {return false;}
        }
        if (spaceId != null && !spaceId.equals(ch.spaceId())) {
            return false;
        }
        if (topLevelOnly && ch.spaceId() != null) {
            return false;
        }
        if (metadataKey != null) {
            if (ch.metadata() == null || !metadataValue.equals(ch.metadata().get(metadataKey))) {
                return false;
            }
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().namePattern(namePattern).namePrefix(namePrefix).keyword(keyword)
                            .semantic(semantic).paused(paused).spaceId(spaceId).topLevelOnly(topLevelOnly)
                            .metadataKey(metadataKey).metadataValue(metadataValue);
    }

    public static final class Builder {
        private String          namePattern;
        private String          namePrefix;
        private String          keyword;
        private ChannelSemantic semantic;
        private Boolean         paused;
        private UUID            spaceId;
        private boolean         topLevelOnly;
        private String          metadataKey;
        private String          metadataValue;

        public Builder namePattern(String v)       {
                                                       this.namePattern = v;
                                                       return this;
                                                   }

        public Builder namePrefix(String v)        {
                                                       this.namePrefix = v;
                                                       return this;
                                                   }

        public Builder keyword(String v)           {
                                                       this.keyword = v;
                                                       return this;
                                                   }

        public Builder semantic(ChannelSemantic v) {
                                                       this.semantic = v;
                                                       return this;
                                                   }

        public Builder paused(Boolean v)           {
                                                       this.paused = v;
                                                       return this;
                                                   }

        public Builder spaceId(UUID v)             {
                                                       this.spaceId = v;
                                                       return this;
                                                   }

        public Builder topLevelOnly(boolean v)     {
                                                       this.topLevelOnly = v;
                                                       return this;
                                                   }

        public Builder metadataKey(String v)       {
                                                       this.metadataKey = v;
                                                       return this;
                                                   }

        public Builder metadataValue(String v)     {
                                                       this.metadataValue = v;
                                                       return this;
                                                   }

        public ChannelQuery build() {
            return new ChannelQuery(this);
        }
    }
}
