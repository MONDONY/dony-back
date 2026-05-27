package com.dony.api.addressbook.delivery;

import com.dony.api.addressbook.delivery.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/delivery-addresses")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class DeliveryAddressController {

    private final DeliveryAddressService service;

    public DeliveryAddressController(DeliveryAddressService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<DeliveryAddressDto>> list(@AuthenticationPrincipal String uid) {
        return ResponseEntity.ok(service.findAll(UUID.fromString(uid)));
    }

    @PostMapping
    public ResponseEntity<DeliveryAddressDto> create(
            @AuthenticationPrincipal String uid,
            @Valid @RequestBody CreateDeliveryAddressRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(UUID.fromString(uid), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeliveryAddressDto> update(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDeliveryAddressRequest request) {
        return ResponseEntity.ok(service.update(UUID.fromString(uid), id, request));
    }

    @PatchMapping("/{id}/set-default")
    public ResponseEntity<DeliveryAddressDto> setDefault(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.setDefault(UUID.fromString(uid), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id) {
        service.delete(UUID.fromString(uid), id);
        return ResponseEntity.noContent().build();
    }
}
