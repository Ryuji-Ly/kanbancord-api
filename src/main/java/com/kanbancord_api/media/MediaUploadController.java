package com.kanbancord_api.media;

import com.kanbancord_api.access.Authorizer;
import com.kanbancord_api.access.ResourceValidator;
import com.kanbancord_api.config.RateLimitProperties;
import com.kanbancord_api.exception.AccessDeniedException;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.TooManyRequestsException;
import com.kanbancord_api.security.CurrentUser;
import com.kanbancord_api.security.TokenBucketRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Uploads an image or video for a board's task descriptions. The file goes straight on to Imgur and
 * only its link comes back, so nothing is kept on this server beyond a record of the upload.
 */
@RestController
@RequestMapping("/api/servers/{serverId}/boards/{boardId}/media")
public class MediaUploadController {

    private final ImgurClient imgurClient;
    private final ImgurProperties imgurProperties;
    private final Authorizer authorizer;
    private final ResourceValidator resourceValidator;
    /** Uploads have their own limit, on top of the general write limit. */
    private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter();
    private final RateLimitProperties rateLimitProperties;
    private final JdbcTemplate jdbcTemplate;

    public MediaUploadController(
            ImgurClient imgurClient,
            ImgurProperties imgurProperties,
            Authorizer authorizer,
            ResourceValidator resourceValidator,
            RateLimitProperties rateLimitProperties,
            JdbcTemplate jdbcTemplate) {
        this.imgurClient = imgurClient;
        this.imgurProperties = imgurProperties;
        this.authorizer = authorizer;
        this.resourceValidator = resourceValidator;
        this.rateLimitProperties = rateLimitProperties;
        this.jdbcTemplate = jdbcTemplate;
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

        DetectedMedia media = detect(file);
        long limit = media.kind() == DetectedMedia.Kind.VIDEO
                ? imgurProperties.getMaxVideoBytes()
                : imgurProperties.getMaxImageBytes();
        if (file.getSize() > limit) {
            throw new BadRequestException((media.kind() == DetectedMedia.Kind.VIDEO ? "Videos" : "Images")
                    + " can be at most " + limit / (1024 * 1024) + " MB");
        }

        long retryAfter = rateLimitProperties.isEnabled()
                ? rateLimiter.tryAcquire("user:" + userId + ":upload", rateLimitProperties.getUpload())
                : 0;
        if (retryAfter > 0) {
            throw new TooManyRequestsException("Too many uploads, try again in " + retryAfter + "s", retryAfter);
        }

        ImgurClient.ImgurUpload uploaded = imgurClient.upload(file, file.getSize(), filename(media), media);
        jdbcTemplate.update("""
                INSERT INTO media_uploads (server_id, board_id, user_id, kind, content_type, size_bytes, imgur_id,
                    link, delete_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, serverId, boardId, userId, media.kind().name(), media.contentType(), file.getSize(),
                uploaded.id(), uploaded.link(), uploaded.deleteHash());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MediaUploadResponse(uploaded.link(), media.kind(), media.contentType()));
    }

    private static DetectedMedia detect(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("The file is empty");
        }
        byte[] header;
        try (InputStream in = file.getInputStream()) {
            header = in.readNBytes(DetectedMedia.HEADER_BYTES);
        }
        return DetectedMedia.detect(header).orElseThrow(() -> new BadRequestException(
                "Only PNG, JPEG, GIF and WebP images, and MP4, MOV and WebM videos can be uploaded"));
    }

    /** The name Imgur is given: a plain one, since the user's file name is theirs and Imgur may show it. */
    private static String filename(DetectedMedia media) {
        String subtype = media.contentType().substring(media.contentType().indexOf('/') + 1);
        String extension = switch (subtype.toLowerCase(Locale.ROOT)) {
            case "jpeg" -> "jpg";
            case "quicktime" -> "mov";
            default -> subtype;
        };
        return "upload." + extension;
    }
}
