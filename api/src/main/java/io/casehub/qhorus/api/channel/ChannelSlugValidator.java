package io.casehub.qhorus.api.channel;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Validates and utility-parses Qhorus channel name slugs.
 * <p>Public: consumers may call {@link #validateSlugPath} to pre-validate a name
 * before calling {@code create_channel}.
 */
public final class ChannelSlugValidator {

    public static final Pattern SEGMENT_PATTERN =
            Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");
    public static final int MAX_SEGMENT_LENGTH = 80;
    public static final int MAX_NAME_LENGTH = 200;

    /**
     * Validates that {@code name} is a well-formed channel slug path.
     * Every {@code /}-delimited segment must match {@code [a-z][a-z0-9]*(-[a-z0-9]+)*}.
     * Max 80 chars per segment, 200 chars total. UUID-shaped names are rejected.
     *
     * <p>Note: {@code '/'} is the path separator; dot ({@code '.'}) is not a valid segment
     * character. Use hyphens for compound names (e.g. {@code quarkmind-scouting-intel})
     * or slashes for path hierarchy (e.g. {@code quarkmind/scouting/intel}).
     *
     * @throws IllegalArgumentException on any violation, with actionable suggestions for
     *         dot-notation inputs
     */
    public static void validateSlugPath(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Channel name must not be null or blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Channel name exceeds " + MAX_NAME_LENGTH + " chars: '" + name + "'");
        }
        // Reject UUID-shaped names — resolveChannel() tries UUID parse first;
        // a UUID-shaped name makes name-based lookup silently unreachable.
        // Use a flag — throwing inside the try block is caught by the same catch.
        boolean isUuid;
        try {
            UUID.fromString(name);
            isUuid = true;
        } catch (IllegalArgumentException ignored) {
            isUuid = false;
        }
        if (isUuid) {
            throw new IllegalArgumentException(
                    "Channel name must not be UUID-shaped: '" + name + "'");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.length() > MAX_SEGMENT_LENGTH) {
                throw new IllegalArgumentException(
                        "Segment '" + segment + "' exceeds " + MAX_SEGMENT_LENGTH
                        + " chars in channel name '" + name + "'");
            }
            if (!SEGMENT_PATTERN.matcher(segment).matches()) {
                final String message;
                if (segment.contains(".")) {
                    final String hyphen = segment.replace('.', '-');
                    final String hierarchy = segment.replace('.', '/');
                    message = "Invalid channel name segment '" + segment + "' in '" + name
                            + "' — dots are not valid segment characters ('/' is the qhorus path separator, not '.')."
                            + " Suggested alternatives: '" + hyphen + "' (hyphen) or '" + hierarchy + "' (hierarchy)."
                            + " Segment must match [a-z][a-z0-9]*(-[a-z0-9]+)*.";
                } else {
                    message = "Invalid channel name segment '" + segment
                            + "' — must match [a-z][a-z0-9]*(-[a-z0-9]+)*. Full name: '" + name + "'";
                }
                throw new IllegalArgumentException(message);
            }
        }
    }

    /**
     * Returns true iff {@code segment} is a valid single slug segment.
     * Rejects UUID-shaped strings — used in contexts where every segment must be
     * a semantically meaningful slug (e.g. validating auto-channel name patterns).
     * Note: {@link #validateSlugPath} does NOT reject UUID-shaped path segments,
     * only UUID-shaped full names.
     */
    public static boolean isValidSegment(String segment) {
        if (segment == null || segment.isBlank() || segment.length() > MAX_SEGMENT_LENGTH) {
            return false;
        }
        if (!SEGMENT_PATTERN.matcher(segment).matches()) {
            return false;
        }
        try {
            UUID.fromString(segment);
            return false; // UUID-shaped segment — rejected
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    /** Returns the UUID if {@code s} parses as one, null otherwise. */
    public static UUID tryParseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ChannelSlugValidator() {}
}
