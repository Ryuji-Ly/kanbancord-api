package com.kanbancord_api.permission;

public enum PermissionRank {
    ADMIN(1000),
    SERVER_MANAGE(800),
    BOARD_MANAGE(600),
    STANDARD(400),
    READONLY(200);

    private final int weight;

    PermissionRank(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    public boolean canModify(PermissionRank other) {
        return this.weight > other.weight;
    }
}