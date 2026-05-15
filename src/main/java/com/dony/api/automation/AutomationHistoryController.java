package com.dony.api.automation;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.automation.dto.AutomationHistoryResponse;
import com.dony.api.common.DonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/travelers/me/automation-history")
public class AutomationHistoryController {

    private final AutomationRuleService ruleService;
    private final UserRepository userRepository;

    public AutomationHistoryController(AutomationRuleService ruleService, UserRepository userRepository) {
        this.ruleService = ruleService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<AutomationHistoryResponse>> listHistory() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Historique réservé aux voyageurs PRO.");
        }
        return ResponseEntity.ok(ruleService.listHistory(user.getId()));
    }

    @GetMapping("/today-count")
    public ResponseEntity<Map<String, Long>> getTodayCount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated", "Authentification requise");
        }
        UserEntity user = userRepository.findByFirebaseUid(auth.getName())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "User Not Found", "Utilisateur introuvable"));
        if (!user.getRoles().contains(Role.TRAVELER) || !user.isProAccount()) {
            throw new DonyBusinessException(
                    HttpStatus.FORBIDDEN, "pro-required",
                    "PRO account required", "Historique réservé aux voyageurs PRO.");
        }
        return ResponseEntity.ok(Map.of("count", ruleService.countTodayActions(user.getId())));
    }
}
