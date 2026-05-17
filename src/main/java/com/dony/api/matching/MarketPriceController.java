package com.dony.api.matching;

import com.dony.api.matching.dto.MarketPriceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/announcements")
public class MarketPriceController {

    private final MarketPriceService marketPriceService;

    public MarketPriceController(MarketPriceService marketPriceService) {
        this.marketPriceService = marketPriceService;
    }

    @GetMapping("/market-price")
    public ResponseEntity<MarketPriceResponse> getMarketPrice(
            @RequestParam String corridor) {
        return ResponseEntity.ok(marketPriceService.getMarketPrice(corridor));
    }
}
