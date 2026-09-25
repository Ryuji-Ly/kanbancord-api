package com.kanbancord_api.exception;

/** Uploads cannot be taken right now: they are not set up, or Imgur is unreachable or limiting us. */
public class MediaUnavailableException extends RuntimeException {
    public MediaUnavailableException(String message) {
        super(message);
    }
}
