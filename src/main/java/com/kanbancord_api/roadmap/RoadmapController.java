package com.kanbancord_api.roadmap;

import com.kanbancord_api.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public roadmap: anyone may read it, and anyone signed in may add an issue to it. */
@RestController
@RequestMapping("/api/public/roadmap")
@Validated
public class RoadmapController {

    /** An issue to add: a short title, and optionally more detail. */
    public record IssueRequest(
            @NotBlank(message = "Title is required") @Size(max = 200, message = "Title must not exceed 200 characters") String title,
            @Size(max = 2000, message = "Description must not exceed 2000 characters") String description) {
    }

    private final RoadmapService roadmap;

    public RoadmapController(RoadmapService roadmap) {
        this.roadmap = roadmap;
    }

    @GetMapping
    public ResponseEntity<RoadmapResponse> get() {
        return ResponseEntity.ok(roadmap.roadmap());
    }

    @PostMapping("/issues")
    public ResponseEntity<RoadmapResponse.Task> addIssue(@Valid @RequestBody IssueRequest request, @CurrentUser Long userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roadmap.addIssue(userId, request.title(), request.description()));
    }
}
