package com.dony.api.common;

import com.dony.api.auth.UserEntity;

/**
 * Shared text-formatting helpers for matching and alert features.
 * Pure utility — no Spring beans, no state.
 */
public final class MatchingTextUtil {

    private MatchingTextUtil() {}

    public static String buildName(UserEntity user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "Expéditeur" : name;
    }

    public static String buildInitials(UserEntity user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        char f = (first != null && !first.isEmpty()) ? Character.toUpperCase(first.charAt(0)) : '?';
        char l = (last != null && !last.isEmpty()) ? Character.toUpperCase(last.charAt(0)) : '?';
        return "" + f + l;
    }

    /**
     * Truncates {@code text} to {@code maxLen} characters, appending "…" when trimmed.
     * Returns {@code ""} for null input.
     */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    public static String corridorLabel(String departure, String arrival) {
        return departure + " → " + arrival;
    }
}
