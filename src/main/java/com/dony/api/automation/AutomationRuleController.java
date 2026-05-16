package com.dony.api.automation;

import com.dony.api.auth.Role;
import com.dony.api.auth.UserEntity;
import com.dony.api.auth.UserRepository;
import com.dony.api.automation.dto.AutomationRuleResponse;
import com.dony.api.automation.dto.CreateRuleRequest;
import com.dony.api.automation.dto.UpdatePresetRequest;
import com.dony.api.common.DonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/travelers/me/automation-rules")
public class AutomationRuleController {

    private final AutomationRuleService ruleService;
    private final UserRepository userRepository;

    public AutomationRuleController(AutomationRuleService ruleService, UserRepository userRepository) {
        this.ruleService = ruleService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<AutomationRuleResponse>> listRules() {
        UserEntity traveler = requireProTraveler();
        return ResponseEntity.ok(ruleService.listRules(traveler.getId()));
    }

    @PostMapping
    public ResponseEntity<AutomationRuleResponse> createRule(
            @Valid @RequestBody CreateRuleRequest body) {
        UserEntity traveler = requireProTraveler();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ruleService.createRule(traveler.getId(), body));
    }

    /**
     * Handles both preset toggles (id = presetRuleId string) and custom rule updates (id = UUID).
     * The frontend sends different payload shapes: { enabled, config? } for presets,
     * { name, conditions, action } for custom rules.
     */
    @PutMapping("/{id}")
    public ResponseEntity<AutomationRuleResponse> updateRule(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        UserEntity traveler = requireProTraveler();

        if (isPresetId(id)) {
            boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
            @SuppressWarnings("unchecked")
            Map<String, Object> config = body.containsKey("config")
                    ? (Map<String, Object>) body.get("config") : null;
            return ResponseEntity.ok(
                    ruleService.updatePreset(traveler.getId(), id, new UpdatePresetRequest(enabled, config)));
        }

        UUID ruleId = parseUuid(id);
        @SuppressWarnings("unchecked")
        var conditions = body.containsKey("conditions")
                ? (List<Map<String, Object>>) body.get("conditions") : List.<Map<String, Object>>of();
        @SuppressWarnings("unchecked")
        var action = body.containsKey("action")
                ? (Map<String, Object>) body.get("action") : Map.<String, Object>of();
        String name = body.containsKey("name") ? (String) body.get("name") : "";
        return ResponseEntity.ok(
                ruleService.updateCustomRule(traveler.getId(), ruleId,
                        new CreateRuleRequest(name, conditions, action)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        UserEntity traveler = requireProTraveler();
        ruleService.deleteRule(traveler.getId(), parseUuid(id));
        return ResponseEntity.noContent().build();
    }

    private boolean isPresetId(String id) {
        try {
            UUID.fromString(id);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new DonyBusinessException(HttpStatus.BAD_REQUEST, "invalid-id",
                    "Invalid ID", "L'identifiant fourni n'est pas un UUID valide.");
        }
    }

    private UserEntity requireProTraveler() {
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
                    "PRO account required", "Automatisations réservées aux voyageurs PRO.");
        }
        return user;
    }
}
