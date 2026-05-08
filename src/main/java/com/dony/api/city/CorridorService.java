package com.dony.api.city;

import com.dony.api.city.dto.PopularCorridorResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CorridorService {

    private final CorridorRepository corridorRepository;

    public CorridorService(CorridorRepository corridorRepository) {
        this.corridorRepository = corridorRepository;
    }

    public List<PopularCorridorResponse> getPopular(int limit) {
        int effective = Math.min(limit, 20);
        return corridorRepository.findTopByUsageCount(effective).stream()
            .map(e -> new PopularCorridorResponse(
                e.getDepartureCity(),
                e.getDepartureCountry(),
                e.getArrivalCity(),
                e.getArrivalCountry()
            ))
            .toList();
    }

    @Transactional
    public void upsertCorridor(String depCity, String depCountry,
                                String arrCity, String arrCountry) {
        if (corridorRepository.existsByDepartureCityAndArrivalCity(depCity, arrCity)) {
            corridorRepository.incrementUsageCount(depCity, arrCity);
        } else {
            CorridorEntity entity = new CorridorEntity();
            entity.setDepartureCity(depCity);
            entity.setDepartureCountry(depCountry != null ? depCountry : "");
            entity.setArrivalCity(arrCity);
            entity.setArrivalCountry(arrCountry != null ? arrCountry : "");
            corridorRepository.save(entity);
        }
    }
}
