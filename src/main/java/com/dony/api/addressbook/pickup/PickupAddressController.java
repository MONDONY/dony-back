package com.dony.api.addressbook.pickup;

import com.dony.api.addressbook.pickup.dto.CreatePickupAddressRequest;
import com.dony.api.addressbook.pickup.dto.PickupAddressDto;
import com.dony.api.addressbook.pickup.dto.UpdatePickupAddressRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/pickup-addresses")
@PreAuthorize("hasAnyRole('SENDER', 'TRAVELER')")
public class PickupAddressController {

    private final PickupAddressService service;

    public PickupAddressController(PickupAddressService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PickupAddressDto>> list(@AuthenticationPrincipal String uid) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.findAll(userId));
    }

    @PostMapping
    public ResponseEntity<PickupAddressDto> create(
            @AuthenticationPrincipal String uid,
            @Valid @RequestBody CreatePickupAddressRequest request) {
        UUID userId = UUID.fromString(uid);
        PickupAddressDto dto = service.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PickupAddressDto> update(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePickupAddressRequest request) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.update(userId, id, request));
    }

    @PatchMapping("/{id}/set-default")
    public ResponseEntity<PickupAddressDto> setDefault(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.setDefault(userId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id) {
        UUID userId = UUID.fromString(uid);
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
