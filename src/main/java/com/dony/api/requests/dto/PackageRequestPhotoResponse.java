package com.dony.api.requests.dto;

import java.util.UUID;

/**
 * Photo de colis d'une demande. {@code objectKey} = clé S3 brute (permet au
 * propriétaire de ré-éditer sa demande sans perdre les photos existantes) ;
 * {@code url} = URL présignée pour l'affichage.
 */
public record PackageRequestPhotoResponse(UUID id, String objectKey, String url) {}
