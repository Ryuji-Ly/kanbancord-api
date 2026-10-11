package com.kanbancord_api.permission;

import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** One-step permission setups for a server or a board; see {@link PermissionPresetService}. */
@RestController
public class PermissionPresetController {

    private final PermissionPresetService presets;

    public PermissionPresetController(PermissionPresetService presets) {
        this.presets = presets;
    }

    @PostMapping("/api/servers/{serverId}/permissions/presets")
    public ResponseEntity<PermissionPresetService.PresetResult> apply(
            @PathVariable Long serverId,
            @CurrentUser Long userId,
            @RequestBody PermissionPresetService.PresetRequest request) {
        return ResponseEntity.ok(presets.apply(serverId, userId, request));
    }
}
