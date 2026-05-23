package com.dony.api.auth;

import com.dony.api.auth.dto.BlockRequest;
import com.dony.api.auth.dto.BlockedUserDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users/me/blocks")
public class BlockController {

    private final BlockService blockService;
    private final AuthService authService;

    public BlockController(BlockService blockService, AuthService authService) {
        this.blockService = blockService;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<List<BlockedUserDto>> list() {
        return ResponseEntity.ok(blockService.listBlocked(authService.requireUserId()));
    }

    @PostMapping
    public ResponseEntity<Void> block(@Valid @RequestBody BlockRequest request) {
        blockService.block(authService.requireUserId(), request.blockedUserId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> unblock(@PathVariable UUID userId) {
        blockService.unblock(authService.requireUserId(), userId);
        return ResponseEntity.noContent().build();
    }
}
