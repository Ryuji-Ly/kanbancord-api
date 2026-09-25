package com.kanbancord_api.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanbancord_api.exception.BadRequestException;
import com.kanbancord_api.exception.MediaUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Uploads files to Imgur anonymously, as the application registered with the Client ID.
 *
 * <p>The request is written by hand so it carries an exact Content-Length: the file streams from
 * where Tomcat stored the upload, never whole in memory, and Imgur is not sent a chunked body.
 */
@Component
public class ImgurClient {

    private static final Logger log = LoggerFactory.getLogger(ImgurClient.class);
    /** Links Imgur hands out for uploaded files; anything else in a response is not trusted. */
    static final String LINK_PREFIX = "https://i.imgur.com/";
    /** Videos can take a while for Imgur to take in. */
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final ImgurProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ImgurClient(ImgurProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** A file on Imgur: its id, public link, and the hash that deletes it. */
    public record ImgurUpload(String id, String link, String deleteHash) {
    }

    public ImgurUpload upload(InputStreamSource file, long size, String filename, DetectedMedia media) {
        if (!properties.isConfigured()) {
            throw new MediaUnavailableException("Image uploads are not set up on this KanbanCord instance.");
        }
        String boundary = "kanbancord-" + UUID.randomUUID();
        String field = media.kind() == DetectedMedia.Kind.VIDEO ? "video" : "image";
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"type\"\r\n\r\nfile\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + media.contentType() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);

        HttpRequest.BodyPublisher content = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        return file.getInputStream();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }), size);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getApiBaseUrl() + "/3/upload"))
                .timeout(TIMEOUT)
                .header("Authorization", "Client-ID " + properties.getClientId())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(head),
                        content,
                        HttpRequest.BodyPublishers.ofByteArray(tail)))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | UncheckedIOException ex) {
            log.warn("Imgur upload failed: {}", ex.getMessage());
            throw new MediaUnavailableException("Imgur could not be reached. Try again in a moment.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MediaUnavailableException("The upload was interrupted. Try again in a moment.");
        }
        if (response.statusCode() != 200) {
            throw refused(response.statusCode());
        }

        JsonNode data;
        try {
            data = objectMapper.readTree(response.body()).path("data");
        } catch (IOException ex) {
            data = null;
        }
        String link = data == null ? "" : data.path("link").asText("");
        String id = data == null ? "" : data.path("id").asText("");
        if (id.isBlank() || !link.startsWith(LINK_PREFIX)) {
            log.warn("Imgur upload returned an unexpected response");
            throw new MediaUnavailableException("Imgur did not return a link for the file. Try again in a moment.");
        }
        String deleteHash = data.path("deletehash").asText("");
        return new ImgurUpload(id, link, deleteHash.isBlank() ? null : deleteHash);
    }

    private static RuntimeException refused(int status) {
        log.warn("Imgur refused an upload with status {}", status);
        if (status == 429) {
            return new MediaUnavailableException("Imgur is limiting uploads right now. Try again later.");
        }
        if (status == 400) {
            return new BadRequestException("Imgur did not accept the file. It may be damaged or in a format Imgur cannot play.");
        }
        if (status == 401 || status == 403) {
            return new MediaUnavailableException("Image uploads are not set up correctly on this KanbanCord instance.");
        }
        return new MediaUnavailableException("Imgur could not take the file right now. Try again in a moment.");
    }
}
