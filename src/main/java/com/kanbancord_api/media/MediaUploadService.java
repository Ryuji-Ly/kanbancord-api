package com.kanbancord_api.media;

import com.kanbancord_api.config.RateLimitProperties;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.TooManyRequestsException;
import com.kanbancord_api.security.TokenBucketRateLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Uploading an image or video for a board's task descriptions: checks what the file is and how big,
 * passes it straight on to Imgur, and keeps only a record of the upload. Whether the person may
 * upload to the board is checked before.
 */
@Service
public class MediaUploadService {

    private final ImgurClient imgurClient;
    private final ImgurProperties imgurProperties;
    private final RateLimitProperties rateLimitProperties;
    private final JdbcTemplate jdbcTemplate;
    /** Uploads have their own limit, on top of the general write limit. */
    private final TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter();

    public MediaUploadService(ImgurClient imgurClient, ImgurProperties imgurProperties,
                              RateLimitProperties rateLimitProperties, JdbcTemplate jdbcTemplate) {
        this.imgurClient = imgurClient;
        this.imgurProperties = imgurProperties;
        this.rateLimitProperties = rateLimitProperties;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MediaUploadResponse upload(Long serverId, Long boardId, Long userId, MultipartFile file) throws IOException {
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
        return new MediaUploadResponse(uploaded.link(), media.kind(), media.contentType());
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
