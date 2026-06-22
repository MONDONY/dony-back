package com.dony.api.favorites;

import com.dony.api.common.DonyBusinessException;
import org.springframework.http.HttpStatus;

public enum FavoriteTargetType {
    TRIP,
    PACKAGE_REQUEST;

    /**
     * "trip" → TRIP, "package-request" → PACKAGE_REQUEST.
     * Throws {@link DonyBusinessException} (400) for any unknown path segment so that
     * the caller gets a scoped RFC 7807 bad-request — without polluting the global
     * {@link com.dony.api.common.GlobalExceptionHandler} with a catch-all for
     * {@link IllegalArgumentException} (which would silently downgrade internal errors).
     */
    public static FavoriteTargetType fromPath(String path) {
        return switch (path) {
            case "trip" -> TRIP;
            case "package-request" -> PACKAGE_REQUEST;
            default -> throw new DonyBusinessException(
                    HttpStatus.BAD_REQUEST,
                    "favorites/unknown-type",
                    "Unknown Favorite Type",
                    "Type de favori inconnu: " + path);
        };
    }
}
