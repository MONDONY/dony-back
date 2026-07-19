package com.dony.api.payments.wallet;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Page de rebond HTTPS après le checkout GeniusPay (Wave/Orange/MTN Money).
 * GeniusPay n'accepte que des URLs http(s) comme success_url/error_url —
 * jamais un schéma custom (dony://) — cette page sert donc d'intermédiaire
 * qui redirige ensuite vers l'app via deep link. Ne crédite jamais le wallet
 * elle-même : le webhook GeniusPay (GeniusPayWebhookController) reste la
 * seule source de vérité pour le crédit.
 */
@Controller
public class GeniusPayReturnController {

    @GetMapping("/payments/geniuspay/return")
    public String handleReturn(@RequestParam(defaultValue = "error") String status, Model model) {
        String normalizedStatus = "success".equals(status) ? "success" : "error";
        model.addAttribute("status", normalizedStatus);
        model.addAttribute("deepLink", "dony://wallet/topup-return/" + normalizedStatus);
        return "geniuspay/return";
    }
}
