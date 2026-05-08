package com.dony.api.city;

import com.dony.api.city.dto.CitySearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CityService {

    private static final int MAX_LIMIT = 15;
    private final CityRepository cityRepository;

    public CityService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    public List<CitySearchResponse> search(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw new IllegalArgumentException("query must have at least 2 characters");
        }
        int effectiveLimit = Math.min(limit, MAX_LIMIT);
        return cityRepository.searchByName(query.trim(), effectiveLimit)
            .stream()
            .map(e -> new CitySearchResponse(
                e.getName(),
                e.getCountryCode(),
                e.getCountryName(),
                e.getLatitude().doubleValue(),
                e.getLongitude().doubleValue()
            ))
            .toList();
    }
}
