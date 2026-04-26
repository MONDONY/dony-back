package com.dony.api.admin;

import com.dony.api.auth.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    // Story 9.5 — Admin lifts a suspension manually
    @PostMapping("/{userId}/unsuspend")
    public ResponseEntity<Void> unsuspendUser(@PathVariable UUID userId) {
        userService.unsuspendUser(userId);
        return ResponseEntity.noContent().build();
    }
}
