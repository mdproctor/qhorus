package io.casehub.qhorus.api.spi;

@FunctionalInterface
public interface SummaryUpdateHook {
    String update(SummaryUpdateContext context);
}
