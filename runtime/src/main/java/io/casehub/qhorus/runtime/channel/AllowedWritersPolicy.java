package io.casehub.qhorus.runtime.channel;

import java.util.List;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Evaluates channel write ACL ({@code allowedWriters}) for a given sender.
 *
 * <p>The ACL string is a comma-separated list of entries. Each entry is one of:
 * <ul>
 *   <li>An exact sender ID (e.g. {@code agent-abc})</li>
 *   <li>A capability tag (e.g. {@code capability:analysis})</li>
 *   <li>A role tag (e.g. {@code role:agent})</li>
 * </ul>
 * A null or blank ACL means the channel is open to all writers.
 *
 * <p>Callers supply a lazy {@code Supplier<List<String>>} for the sender's tags.
 * For registered Qhorus instances this returns capability tags plus a synthetic
 * {@code role:<actorType>} entry. For external senders (A2A agents) it returns
 * only the synthetic role tag — they have no attested capability tags.
 */
@ApplicationScoped
public class AllowedWritersPolicy {

    /**
     * Returns {@code true} if {@code sender} is permitted to write to a channel
     * with the given {@code allowedWriters} ACL string.
     *
     * @param sender               the sender identifier
     * @param allowedWriters       the channel ACL string (nullable)
     * @param senderTagsSupplier   lazily-evaluated capability and role tags for the sender;
     *                             invoked at most once, only when the ACL contains a tag entry
     */
    public boolean isAllowedWriter(final String sender, final String allowedWriters,
            final Supplier<List<String>> senderTagsSupplier) {
        if (allowedWriters == null || allowedWriters.isBlank()) {
            return true;
        }
        List<String> senderTags = null;
        for (final String raw : allowedWriters.split(",")) {
            final String entry = raw.strip();
            if (entry.isEmpty()) {
                continue;
            }
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
