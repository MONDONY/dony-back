package com.dony.api.city;

import com.dony.api.city.dto.CitySearchResponse;
import com.dony.api.city.dto.PopularCorridorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
@RestController
@RequestMapping("/cities")
public class CityController {

    private final CityService cityService;
    private final CorridorService corridorService;

    public CityController(CityService cityService, CorridorService corridorService) {
        this.cityService = cityService;
        this.corridorService = corridorService;
    }

    @GetMapping("/search")
    public ResponseEntity<List<CitySearchResponse>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.unprocessableEntity().build();
        }
        return ResponseEntity.ok(cityService.search(q, limit));
    }

    @GetMapping("/corridors/popular")
    public ResponseEntity<List<PopularCorridorResponse>> popularCorridors(
            @RequestParam(defaultValue = "6") int limit) {
        return ResponseEntity.ok(corridorService.getPopular(limit));
    }
}
