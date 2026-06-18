package com.dony.api.requests.dto;

import java.util.UUID;

/** Photo de colis d'une demande, URL présignée. */
public record PackageRequestPhotoResponse(UUID id, String url) {}
