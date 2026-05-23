package com.dony.api.settings;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/business-preferences")
public class UserBusinessPrefsController {

    private final UserBusinessPrefsService service;

    public UserBusinessPrefsController(UserBusinessPrefsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<UserBusinessPrefsDto> get(Authentication authentication) {
        return ResponseEntity.ok(service.getPrefs(authentication.getName()));
    }

    @PutMapping
    public ResponseEntity<UserBusinessPrefsDto> put(Authentication authentication,
                                                    @Valid @RequestBody UserBusinessPrefsDto dto) {
        return ResponseEntity.ok(service.upsert(authentication.getName(), dto));
    }
}
