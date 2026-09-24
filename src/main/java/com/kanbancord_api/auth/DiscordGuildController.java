package com.kanbancord_api.auth;

import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The Discord servers the signed-in user is in, fetched by the API so the browser needs no Discord token. */
@RestController
@RequestMapping("/api/me/guilds")
public class DiscordGuildController {

    private final DiscordGuildService discordGuildService;

    public DiscordGuildController(DiscordGuildService discordGuildService) {
        this.discordGuildService = discordGuildService;
    }

    @GetMapping
    public ResponseEntity<List<DiscordGuildResponse>> guilds(@CurrentUser Long userId) {
        return ResponseEntity.ok(discordGuildService.guilds(userId));
    }
}
