package io.casehub.qhorus.api.store.query;

import io.casehub.qhorus.api.watchdog.Watchdog;

public final class WatchdogQuery {

    private final String conditionType;
    private final String tenancyId;

    private WatchdogQuery(Builder b) {
        this.conditionType = b.conditionType;
        this.tenancyId = b.tenancyId;
    }

    public static WatchdogQuery all() {
        return new Builder().build();
    }

    public static WatchdogQuery byConditionType(String conditionType) {
        return new Builder().conditionType(conditionType).build();
    }

    public static WatchdogQuery byTenancy(String tenancyId) {
        return new Builder().tenancyId(tenancyId).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String conditionType() {
        return conditionType;
    }

    public String tenancyId() {
        return tenancyId;
    }

    public boolean matches(Watchdog w) {
        if (conditionType != null && !conditionType.equals(w.conditionType())) {
            return false;
        }
        if (tenancyId != null && !tenancyId.equals(w.tenancyId())) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().conditionType(conditionType).tenancyId(tenancyId);
    }

    public static final class Builder {
        private String conditionType;
        private String tenancyId;

        public Builder conditionType(String v) {
            this.conditionType = v;
            return this;
        }

        public Builder tenancyId(String v) {
            this.tenancyId = v;
            return this;
        }

        public WatchdogQuery build() {
            return new WatchdogQuery(this);
        }
    }
}
