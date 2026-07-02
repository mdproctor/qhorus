package io.casehub.qhorus.runtime.channel;

import java.util.List;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AllowedWritersPolicy {

    public boolean isAllowedWriter(final String sender, final List<String> allowedWriters,
            final Supplier<List<String>> senderTagsSupplier) {
        if (allowedWriters == null || allowedWriters.isEmpty()) {
            return true;
        }
        List<String> senderTags = null;
        for (final String entry : allowedWriters) {
            if (entry == null || entry.isEmpty()) continue;
            if (entry.startsWith("capability:") || entry.startsWith("role:")) {
                if (senderTags == null) {
                    senderTags = senderTagsSupplier.get();
                }
                if (senderTags.contains(entry)) {
                    return true;
                }
            } else {
                if (entry.equals(sender)) {
                    return true;
                }
            }
        }
        return false;
    }
}
