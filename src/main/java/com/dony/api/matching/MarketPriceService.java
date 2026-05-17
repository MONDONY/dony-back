package com.dony.api.matching;

import com.dony.api.matching.dto.MarketPriceResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class MarketPriceService {

    private static final Map<String, String[]> CORRIDOR_MAP = Map.of(
        "PARIS_DAKAR",     new String[]{"Paris", "Dakar"},
        "PARIS_ABIDJAN",   new String[]{"Paris", "Abidjan"},
        "PARIS_BAMAKO",    new String[]{"Paris", "Bamako"},
        "PARIS_DOUALA",    new String[]{"Paris", "Douala"},
        "LYON_ABIDJAN",    new String[]{"Lyon", "Abidjan"},
        "MARSEILLE_DAKAR", new String[]{"Marseille", "Dakar"}
    );

    private final AnnouncementRepository announcementRepository;

    public MarketPriceService(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    public MarketPriceResponse getMarketPrice(String corridor) {
        String[] cities = CORRIDOR_MAP.get(corridor.toUpperCase());
        if (cities == null) {
            return new MarketPriceResponse(null, "EUR");
        }

        List<AnnouncementEntity> recent = announcementRepository.findRecentByCorridor(
            cities[0], cities[1], PageRequest.of(0, 30));

        if (recent.isEmpty()) {
            return new MarketPriceResponse(null, "EUR");
        }

        List<BigDecimal> prices = recent.stream()
            .map(AnnouncementEntity::getPricePerKg)
            .sorted()
            .toList();

        BigDecimal median = computeMedian(prices);
        return new MarketPriceResponse(median.setScale(2, RoundingMode.HALF_UP), "EUR");
    }

    private BigDecimal computeMedian(List<BigDecimal> sorted) {
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1)
            .add(sorted.get(size / 2))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }
}
