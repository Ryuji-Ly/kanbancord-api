package com.kanbancord_api.permission;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class DiscordPermissionParser {

    public Set<DiscordPermissionFlag> parse(long permissionBitset) {
        Set<DiscordPermissionFlag> flags = EnumSet.noneOf(DiscordPermissionFlag.class);
        for (DiscordPermissionFlag flag : DiscordPermissionFlag.values()) {
            if (hasFlag(permissionBitset, flag)) {
                flags.add(flag);
            }
        }
        return flags;
    }

    public boolean hasFlag(long permissionBitset, DiscordPermissionFlag flag) {
        return (permissionBitset & flag.getBit()) == flag.getBit();
    }
}
