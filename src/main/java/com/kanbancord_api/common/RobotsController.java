package com.kanbancord_api.common;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells search engines there is nothing to index here: the API is for the bot and the website, and
 * the public pages are on kanbancord.com. Every response also says so (X-Robots-Tag: noindex).
 */
@RestController
public class RobotsController {

    static final String ROBOTS = """
            # The KanbanCord API: nothing to index here. The website is https://kanbancord.com
            User-agent: *
            Disallow: /
            """;

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok(ROBOTS);
    }
}
