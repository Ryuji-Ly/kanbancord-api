package com.kanbancord_api.unit.service;

import com.kanbancord_api.permission.DiscordPermissionFlag;
import com.kanbancord_api.permission.DiscordPermissionParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordPermissionParserTest {

    private final DiscordPermissionParser parser = new DiscordPermissionParser();

    @Test
    void parse_returnsExpectedFlags_whenBitsetContainsMultiplePermissions() {
        long bitset = DiscordPermissionFlag.ADMINISTRATOR.getBit()
                | DiscordPermissionFlag.BAN_MEMBERS.getBit()
                | DiscordPermissionFlag.MODERATE_MEMBERS.getBit();

        Set<DiscordPermissionFlag> flags = parser.parse(bitset);

        assertTrue(flags.contains(DiscordPermissionFlag.ADMINISTRATOR));
        assertTrue(flags.contains(DiscordPermissionFlag.BAN_MEMBERS));
        assertTrue(flags.contains(DiscordPermissionFlag.MODERATE_MEMBERS));
        assertFalse(flags.contains(DiscordPermissionFlag.MANAGE_GUILD));
    }

    @Test
    void hasFlag_detectsPresenceAndAbsence() {
        long bitset = DiscordPermissionFlag.MANAGE_GUILD.getBit();

        assertTrue(parser.hasFlag(bitset, DiscordPermissionFlag.MANAGE_GUILD));
        assertFalse(parser.hasFlag(bitset, DiscordPermissionFlag.BAN_MEMBERS));
    }
}
