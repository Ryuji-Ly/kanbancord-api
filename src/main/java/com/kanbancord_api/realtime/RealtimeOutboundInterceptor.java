package com.kanbancord_api.realtime;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.NativeMessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Re-checks every broadcast event against the recipient's current access before it is delivered.
 *
 * <p>Subscriptions are only authorized once, when made. This drops events for users who have since
 * lost access, and filters events about a board out of the server topic for users who cannot view
 * that board, so private boards do not leak through the server-wide feed.
 */
@Component
public class RealtimeOutboundInterceptor implements ChannelInterceptor {

    private final RealtimeSessionManager realtimeSessionManager;
    private final RealtimeAccessCache realtimeAccessCache;

    public RealtimeOutboundInterceptor(
            RealtimeSessionManager realtimeSessionManager,
            RealtimeAccessCache realtimeAccessCache) {
        this.realtimeSessionManager = realtimeSessionManager;
        this.realtimeAccessCache = realtimeAccessCache;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        MessageHeaders headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
            return message;
        }

        Optional<RealtimeTopics.Topic> topic = RealtimeTopics.parse(SimpMessageHeaderAccessor.getDestination(headers));
        if (topic.isEmpty()) {
            return message;
        }

        Optional<Long> userId = realtimeSessionManager.userId(SimpMessageHeaderAccessor.getSessionId(headers));
        if (userId.isEmpty()) {
            return null;
        }

        Long serverId = topic.get().serverId();
        Long boardId = topic.get().isBoardTopic() ? topic.get().boardId() : boardIdHeader(headers);
        return realtimeAccessCache.canView(userId.get(), serverId, boardId) ? message : null;
    }

    private static Long boardIdHeader(MessageHeaders headers) {
        String value = NativeMessageHeaderAccessor.getFirstNativeHeader(RealtimeTopics.BOARD_ID_HEADER, headers);
        return value == null ? null : Long.valueOf(value);
    }
}
