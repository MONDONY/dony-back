package com.dony.api.addressbook.favorite;

import com.dony.api.addressbook.favorite.dto.AddFavoriteTravelerRequest;
import com.dony.api.addressbook.favorite.dto.FavoriteTravelerDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/addressbook/favorite-travelers")
@PreAuthorize("hasRole('SENDER')")
public class FavoriteTravelerController {

    private final FavoriteTravelerService service;

    public FavoriteTravelerController(FavoriteTravelerService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<FavoriteTravelerDto>> list(@AuthenticationPrincipal String uid) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.findAll(userId));
    }

    @PostMapping
    public ResponseEntity<FavoriteTravelerDto> add(
            @AuthenticationPrincipal String uid,
            @Valid @RequestBody AddFavoriteTravelerRequest request) {
        UUID userId = UUID.fromString(uid);
        FavoriteTravelerDto dto = service.add(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @DeleteMapping("/{travelerId}")
    public ResponseEntity<Void> remove(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID travelerId) {
        UUID userId = UUID.fromString(uid);
        service.remove(userId, travelerId);
        return ResponseEntity.noContent().build();
    }
}
