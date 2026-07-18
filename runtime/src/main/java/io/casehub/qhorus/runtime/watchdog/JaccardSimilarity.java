package io.casehub.qhorus.runtime.watchdog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

final class JaccardSimilarity {

    private static final String PUNCTUATION = "{}[]():\",\'=;";

    static double similarity(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        Set<String> tokensA = tokenize(a);
        Set<String> tokensB = tokenize(b);
        if (tokensA.isEmpty() && tokensB.isEmpty()) {
            return 1.0;
        }
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0.0;
        }
        long intersection = tokensA.stream().filter(tokensB::contains).count();
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return (double) intersection / union.size();
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.split("\\s+"))
                .map(t -> stripPunctuation(t).toLowerCase())
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String stripPunctuation(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (PUNCTUATION.indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private JaccardSimilarity() {}
}
