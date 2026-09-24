package com.kanbancord_api.realtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The broadcast topics clients can subscribe to: one per server and one per board.
 */
public final class RealtimeTopics {

    /** Native header naming the board an event on the server topic is about, used to filter recipients. */
    public static final String BOARD_ID_HEADER = "x-kanbancord-board-id";

    /** The per-user queue for changes that concern one user only; clients subscribe to {@code /user/queue/me}. */
    public static final String USER_QUEUE = "/queue/me";

    private static final Pattern SERVER_TOPIC_PATTERN = Pattern.compile("^/topic/servers/(\\d+)$");
    private static final Pattern BOARD_TOPIC_PATTERN = Pattern.compile("^/topic/servers/(\\d+)/boards/(\\d+)$");

    private RealtimeTopics() {
    }

    public static String serverTopic(Long serverId) {
        return "/topic/servers/" + serverId;
    }

    public static String boardTopic(Long serverId, Long boardId) {
        return "/topic/servers/" + serverId + "/boards/" + boardId;
    }

    /** The server and board (null for the server topic) a destination refers to, if it is one of our topics. */
    public static Optional<Topic> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }

        Matcher boardMatcher = BOARD_TOPIC_PATTERN.matcher(destination);
        if (boardMatcher.matches()) {
            return Optional.of(new Topic(Long.parseLong(boardMatcher.group(1)), Long.parseLong(boardMatcher.group(2))));
        }

        Matcher serverMatcher = SERVER_TOPIC_PATTERN.matcher(destination);
        if (serverMatcher.matches()) {
            return Optional.of(new Topic(Long.parseLong(serverMatcher.group(1)), null));
        }

        return Optional.empty();
    }

    public record Topic(Long serverId, Long boardId) {

        public boolean isBoardTopic() {
            return boardId != null;
        }
    }
}
