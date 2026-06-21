package com.dony.api.notifications;

/** État du toggle « notifier quand un colis matche un de mes trajets » (cloche voyageur). */
public record PackageMatchAlertDto(boolean enabled) {}
