package com.dony.api.auth;

import com.dony.api.auth.dto.UserResponse;
import com.dony.api.common.DonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users/me/roles")
public class UserRoleController {

    private final UserRoleService userRoleService;

    public UserRoleController(UserRoleService userRoleService) {
        this.userRoleService = userRoleService;
    }

    @PostMapping("/traveler/activate")
    @PreAuthorize("hasRole('SENDER')")
    public ResponseEntity<UserResponse> activateTraveler() {
        String firebaseUid = requireFirebaseUid();
        return ResponseEntity.ok(userRoleService.activateTravelerRole(firebaseUid));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Unauthorized", "Token Firebase requis");
        }
        return (String) auth.getPrincipal();
    }
}
