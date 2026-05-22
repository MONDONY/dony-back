package com.dony.api.auth;

import com.dony.api.auth.dto.UserDeviceDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/users/me/devices")
public class ConnectedDevicesController {

    private final ConnectedDevicesService devicesService;
    private final AuthService authService;

    public ConnectedDevicesController(ConnectedDevicesService devicesService,
                                       AuthService authService) {
        this.devicesService = devicesService;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<List<UserDeviceDto>> listDevices(HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = request.getHeader("X-Device-Id");
        return ResponseEntity.ok(devicesService.listDevices(userId, currentDeviceId));
    }

    @DeleteMapping("/others")
    public ResponseEntity<Void> revokeOthers(HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = request.getHeader("X-Device-Id");
        devicesService.revokeOthers(userId, currentDeviceId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revokeDevice(@PathVariable String deviceId,
                                              HttpServletRequest request) {
        UUID userId = authService.requireUserId();
        String currentDeviceId = request.getHeader("X-Device-Id");
        devicesService.revokeDevice(userId, deviceId, currentDeviceId);
        return ResponseEntity.noContent().build();
    }
}
