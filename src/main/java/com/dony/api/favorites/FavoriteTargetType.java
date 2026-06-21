package com.dony.api.favorites;

public enum FavoriteTargetType {
    TRIP,
    PACKAGE_REQUEST;

    /** "trip" -> TRIP, "package-request" -> PACKAGE_REQUEST. Lève IllegalArgumentException sinon. */
    public static FavoriteTargetType fromPath(String path) {
        return switch (path) {
            case "trip" -> TRIP;
            case "package-request" -> PACKAGE_REQUEST;
            default -> throw new IllegalArgumentException("Type de favori inconnu: " + path);
        };
    }
}
