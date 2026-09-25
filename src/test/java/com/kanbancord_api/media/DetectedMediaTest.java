package com.kanbancord_api.media;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectedMediaTest {

    @Test
    void recognisesImagesAndVideosByTheirFirstBytes() {
        assertEquals("image/png", type(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0}));
        assertEquals("image/jpeg", type(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals("image/gif", type(ascii("GIF89a......")));
        assertEquals("image/webp", type(ascii("RIFF\0\0\0\0WEBPVP8 ")));
        assertEquals("video/mp4", type(ascii("\0\0\0 ftypisom\0\0\0\0")));
        assertEquals("video/quicktime", type(ascii("\0\0\0\u0014ftypqt  \0\0\0\0")));
        assertEquals("video/webm", type(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0}));
        assertEquals(DetectedMedia.Kind.VIDEO, DetectedMedia.detect(ascii("\0\0\0 ftypmp42")).orElseThrow().kind());
    }

    @Test
    void rejectsEverythingElse_whateverItIsCalled() {
        assertTrue(DetectedMedia.detect(ascii("<svg xmlns=")).isEmpty(), "SVG can carry scripts");
        assertTrue(DetectedMedia.detect(ascii("<!DOCTYPE html>")).isEmpty());
        assertTrue(DetectedMedia.detect(ascii("%PDF-1.7")).isEmpty());
        assertTrue(DetectedMedia.detect(new byte[]{(byte) 0x89, 'P'}).isEmpty(), "too short to tell");
        assertTrue(DetectedMedia.detect(new byte[0]).isEmpty());
    }

    private static String type(byte[] header) {
        Optional<DetectedMedia> media = DetectedMedia.detect(header);
        return media.map(DetectedMedia::contentType).orElse("none");
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
