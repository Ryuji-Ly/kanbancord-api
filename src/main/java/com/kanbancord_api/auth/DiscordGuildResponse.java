package com.kanbancord_api.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A Discord server the user is in, as Discord lists it. {@code permissions} is the user's permission bits. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscordGuildResponse(String id, String name, String icon, boolean owner, String permissions) {
}
