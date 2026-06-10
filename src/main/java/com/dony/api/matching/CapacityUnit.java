package com.dony.api.matching;

public enum CapacityUnit {
    SUITCASE_23KG,
    SUITCASE_32KG,
    KG_FREE,
    // Capacité personnalisée : le voyageur saisit un nombre de kg exact. Bornée
    // comme les valises (availableKg = la valeur saisie) — pas de traitement
    // spécial : seul KG_FREE est non borné.
    KG_EXACT
}
