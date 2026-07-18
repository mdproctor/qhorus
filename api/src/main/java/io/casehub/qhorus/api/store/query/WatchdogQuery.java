package io.casehub.qhorus.api.store.query;

import io.casehub.qhorus.api.watchdog.Watchdog;
import io.casehub.qhorus.api.watchdog.WatchdogConditionType;

public final class WatchdogQuery {

    private final WatchdogConditionType conditionType;
    private final String                tenancyId;

    private WatchdogQuery(Builder b) {
        this.conditionType = b.conditionType;
        this.tenancyId     = b.tenancyId;
    }

    public static WatchdogQuery all() {
        return new Builder().build();
    }

    public static WatchdogQuery byConditionType(WatchdogConditionType conditionType) {
        return new Builder().conditionType(conditionType).build();
    }

    public static WatchdogQuery byTenancy(String tenancyId) {
        return new Builder().tenancyId(tenancyId).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public WatchdogConditionType conditionType() {
        return conditionType;
    }

    public String tenancyId() {
        return tenancyId;
    }

    public boolean matches(Watchdog w) {
        if (conditionType != null && conditionType != w.conditionType()) {
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
        private WatchdogConditionType conditionType;
        private String                tenancyId;

        public Builder conditionType(WatchdogConditionType v) {
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
