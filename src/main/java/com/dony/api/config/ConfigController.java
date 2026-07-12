package com.dony.api.config;

import com.dony.api.config.dto.CommissionRateResponse;
import com.dony.api.config.dto.ContentCategoryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/config")
public class ConfigController {

    private final DonyConfigProperties config;

    public ConfigController(DonyConfigProperties config) {
        this.config = config;
    }

    @GetMapping("/commission-rate")
    public ResponseEntity<CommissionRateResponse> getCommissionRate() {
        return ResponseEntity.ok(new CommissionRateResponse(config.commission().rate()));
    }

    @GetMapping("/content-categories")
    public ResponseEntity<List<ContentCategoryResponse>> getContentCategories() {
        return ResponseEntity.ok(ContentCatalog.CATEGORIES);
    }
}
