package com.dony.api.addressbook.recipient;

import com.dony.api.addressbook.recipient.dto.CreateRecipientRequest;
import com.dony.api.addressbook.recipient.dto.RecipientDto;
import com.dony.api.addressbook.recipient.dto.UpdateRecipientRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController("addressbookRecipientController")
@RequestMapping("/addressbook/recipients")
@PreAuthorize("hasRole('SENDER')")
public class RecipientController {

    private final RecipientService service;

    public RecipientController(RecipientService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<RecipientDto>> list(@AuthenticationPrincipal String uid) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.findAll(userId));
    }

    @PostMapping
    public ResponseEntity<RecipientDto> create(
            @AuthenticationPrincipal String uid,
            @Valid @RequestBody CreateRecipientRequest request) {
        UUID userId = UUID.fromString(uid);
        RecipientDto dto = service.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecipientDto> update(
            @AuthenticationPrincipal String uid,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRecipientRequest request) {
        UUID userId = UUID.fromString(uid);
        return ResponseEntity.ok(service.update(userId, id, request));
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
