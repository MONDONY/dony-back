package com.dony.api.addressbook.delivery;

import com.dony.api.addressbook.delivery.dto.*;
import com.dony.api.auth.UserRepository;
import com.dony.api.common.DonyBusinessException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/delivery-addresses")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class DeliveryAddressController {

    private final DeliveryAddressService service;
    private final UserRepository userRepository;

    public DeliveryAddressController(DeliveryAddressService service, UserRepository userRepository) {
        this.service = service;
        this.userRepository = userRepository;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryAddressDto>> list() {
        return ResponseEntity.ok(service.findAll(resolveUserId()));
    }

    @PostMapping
    public ResponseEntity<DeliveryAddressDto> create(
            @Valid @RequestBody CreateDeliveryAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(resolveUserId(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeliveryAddressDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryAddressRequest request) {
        return ResponseEntity.ok(service.update(resolveUserId(), id, request));
    }

    @PatchMapping("/{id}/set-default")
    public ResponseEntity<DeliveryAddressDto> setDefault(
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.setDefault(resolveUserId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id) {
        service.delete(resolveUserId(), id);
        return ResponseEntity.noContent().build();
    }

    private UUID resolveUserId() {
        String firebaseUid = requireFirebaseUid();
        return userRepository.findByFirebaseUid(firebaseUid)
                .map(u -> u.getId())
                .orElseThrow(() -> new DonyBusinessException(
                        HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Utilisateur introuvable"));
    }

    private String requireFirebaseUid() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new DonyBusinessException(
                    HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Un token Firebase valide est requis");
        }
        return (String) auth.getPrincipal();
    }
}
