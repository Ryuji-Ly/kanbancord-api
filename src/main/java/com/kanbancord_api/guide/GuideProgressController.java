package com.kanbancord_api.guide;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** How far a server has come, for the ticks in the bot's /guide: one answer any member can ask for. */
@RestController
public class GuideProgressController {

    private final GuideProgressService progress;
    private final Authorizer authorizer;

    public GuideProgressController(GuideProgressService progress, Authorizer authorizer) {
        this.progress = progress;
        this.authorizer = authorizer;
    }

    @GetMapping("/api/servers/{serverId}/guide")
    public ResponseEntity<GuideProgressService.GuideProgress> get(@PathVariable Long serverId, @CurrentUser Long userId) {
        authorizer.requireUserInServer(userId, serverId);
        return ResponseEntity.ok(progress.progress(serverId, userId));
    }
}
