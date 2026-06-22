package com.dony.api.favorites.dto;

import java.util.Set;
import java.util.UUID;

public record FavoriteIdsResponse(Set<UUID> trips, Set<UUID> packageRequests) {}
