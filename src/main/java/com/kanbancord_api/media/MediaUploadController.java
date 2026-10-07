package com.kanbancord_api.media;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Uploads an image or video for a board's task descriptions. The file goes straight on to Imgur and
 * only its link comes back, so nothing is kept on this server beyond a record of the upload.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/media")
public class MediaUploadController {

    private final MediaUploadService uploads;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;

    public MediaUploadController(MediaUploadService uploads, Authorizer authorizer, ResourceValidator resourceValidator) {
        this.uploads = uploads;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
    }

    /** Anyone who can write a description on the board: creating tasks or editing them. */
    @PostMapping
    public ResponseEntity<MediaUploadResponse> upload(
            @PathVariable Long serverId,
            @PathVariable Long boardId,
            @CurrentUser Long userId,
            @RequestParam("file") MultipartFile file) throws IOException {
        resourceValidator.requireBoardInServer(boardId, serverId);
        try {
            authorizer.requireBoardPermission(userId, serverId, boardId, "EDIT_TASK");
        } catch (AccessDeniedException ex) {
            authorizer.requireBoardPermission(userId, serverId, boardId, "CREATE_TASK");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(uploads.upload(serverId, boardId, userId, file));
    }
}
