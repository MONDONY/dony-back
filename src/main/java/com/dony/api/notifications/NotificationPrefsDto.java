package com.dony.api.notifications;

public record NotificationPrefsDto(
    boolean pushActivityBids,
    boolean pushActivityNegotiations,
    boolean pushMessages,
    boolean pushTripReminder,
    boolean pushPromo
) {
    public static NotificationPrefsDto defaults() {
        return new NotificationPrefsDto(true, true, true, true, false);
    }
}
