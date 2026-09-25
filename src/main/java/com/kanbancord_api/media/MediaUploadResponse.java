package com.kanbancord_api.media;

/**
 * An uploaded file, ready to go into markdown: {@code ![name](url)} shows an image, and the web app
 * plays a video written the same way.
 */
public record MediaUploadResponse(String url, DetectedMedia.Kind kind, String contentType) {
}
