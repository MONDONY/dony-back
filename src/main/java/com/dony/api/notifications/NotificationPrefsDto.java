package com.dony.api.notifications;

public record NotificationPrefsDto(
    boolean pushActivityBids,
    boolean pushActivityNegotiations,
    boolean pushMessages,
    boolean pushTripReminder,
    boolean pushPromo,
    boolean pushCorridorAlerts
) {
    public static NotificationPrefsDto defaults() {
        return new NotificationPrefsDto(true, true, true, true, false, true);
    }
}
