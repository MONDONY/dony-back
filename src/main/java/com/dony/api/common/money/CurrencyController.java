package com.dony.api.common.money;

import com.dony.api.common.money.dto.CurrencyResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Référentiel devises pour l'app (affichage INDICATIF uniquement — règle R1 :
 * aucun montant transactionnel n'est calculé côté client). Public via le
 * permitAll existant sur /config/** (même famille que /config/commission-rate).
 */
@RestController
@RequestMapping("/config")
public class CurrencyController {

    private final CurrencyRegistry registry;

    public CurrencyController(CurrencyRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/currencies")
    public List<CurrencyResponse> currencies() {
        return registry.enabledCurrencies().stream().map(CurrencyResponse::from).toList();
    }
}
