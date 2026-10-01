package com.kanbancord_api.permission;

import java.util.Arrays;
import java.util.Optional;

public enum DiscordPermissionFlag {

    CREATE_INSTANT_INVITE(1L << 0),
    KICK_MEMBERS(1L << 1),
    BAN_MEMBERS(1L << 2),
    ADMINISTRATOR(1L << 3),
    MANAGE_CHANNELS(1L << 4),
    MANAGE_GUILD(1L << 5),
    ADD_REACTIONS(1L << 6),
    VIEW_AUDIT_LOG(1L << 7),
    PRIORITY_SPEAKER(1L << 8),
    STREAM(1L << 9),
    VIEW_CHANNEL(1L << 10),
    SEND_MESSAGES(1L << 11),
    SEND_TTS_MESSAGES(1L << 12),
    MANAGE_MESSAGES(1L << 13),
    EMBED_LINKS(1L << 14),
    ATTACH_FILES(1L << 15),
    READ_MESSAGE_HISTORY(1L << 16),
    MENTION_EVERYONE(1L << 17),
    USE_EXTERNAL_EMOJIS(1L << 18),
    VIEW_GUILD_INSIGHTS(1L << 19),
    CONNECT(1L << 20),
    SPEAK(1L << 21),
    MUTE_MEMBERS(1L << 22),
    DEAFEN_MEMBERS(1L << 23),
    MOVE_MEMBERS(1L << 24),
    USE_VAD(1L << 25),
    CHANGE_NICKNAME(1L << 26),
    MANAGE_NICKNAMES(1L << 27),
    MANAGE_ROLES(1L << 28),
    MANAGE_WEBHOOKS(1L << 29),
    MANAGE_GUILD_EXPRESSIONS(1L << 30),
    USE_APPLICATION_COMMANDS(1L << 31),
    REQUEST_TO_SPEAK(1L << 32),
    MANAGE_EVENTS(1L << 33),
    MANAGE_THREADS(1L << 34),
    CREATE_PUBLIC_THREADS(1L << 35),
    CREATE_PRIVATE_THREADS(1L << 36),
    USE_EXTERNAL_STICKERS(1L << 37),
    SEND_MESSAGES_IN_THREADS(1L << 38),
    USE_EMBEDDED_ACTIVITIES(1L << 39),
    MODERATE_MEMBERS(1L << 40),
    VIEW_CREATOR_MONETIZATION_ANALYTICS(1L << 41),
    USE_SOUNDBOARD(1L << 42),
    CREATE_GUILD_EXPRESSIONS(1L << 43),
    CREATE_EVENTS(1L << 44),
    USE_EXTERNAL_SOUNDS(1L << 45),
    SEND_VOICE_MESSAGES(1L << 46),
    SEND_POLLS(1L << 49),
    USE_EXTERNAL_APPS(1L << 50),
    PIN_MESSAGES(1L << 51),
    BYPASS_SLOWMODE(1L << 52);

    private final long bit;

    DiscordPermissionFlag(long bit) {
        this.bit = bit;
    }

    public long getBit() {
        return bit;
    }

    public static Optional<DiscordPermissionFlag> fromBit(Long bit) {
        return Arrays.stream(values()).filter(flag -> bit != null && flag.bit == bit).findFirst();
    }
}
