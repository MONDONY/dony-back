package com.dony.api.common;

import com.dony.api.auth.UserEntity;

/**
 * Shared text-formatting helpers for matching and alert features.
 * Pure utility — no Spring beans, no state.
 */
public final class MatchingTextUtil {

    private MatchingTextUtil() {}

    /**
     * Nom complet, réservé au back-office admin (modération, litiges, support).
     *
     * <p>Ne jamais l'employer pour un écran utilisateur : la contrepartie n'a pas à connaître
     * le patronyme entier, {@link #buildPublicName(UserEntity)} est là pour ça. Le repli est
     * le username du compte plutôt que « Expéditeur », qui empêchait un modérateur de
     * distinguer deux comptes sans nom dans une même liste.
     */
    public static String buildName(UserEntity user) {
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        String name = (first + " " + last).trim();
        return name.isEmpty() ? user.publicDisplayName() : name;
    }

    /**
     * Nom montré à une contrepartie : « Prénom N. », ou le username du compte.
     *
     * <p>Délègue à {@link UserEntity#publicDisplayName()} pour que tous les écrans utilisateur
     * nomment un même compte de la même façon.
     */
    public static String buildPublicName(UserEntity user) {
        return user.publicDisplayName();
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
