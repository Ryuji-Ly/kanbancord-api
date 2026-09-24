package com.kanbancord_api.exception;

/** The API holds no usable Discord authorization for the user; signing in again grants a new one. */
public class DiscordReauthorizationRequiredException extends RuntimeException {
    public DiscordReauthorizationRequiredException() {
        super("Discord authorization has expired. Sign in again to load your servers.");
    }
}
