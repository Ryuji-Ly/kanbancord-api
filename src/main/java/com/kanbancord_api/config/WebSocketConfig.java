package com.kanbancord_api.config;

import com.kanbancord_api.realtime.RealtimeChannelInterceptor;
import com.kanbancord_api.realtime.RealtimeHandshakeHandler;
import com.kanbancord_api.realtime.RealtimeHandshakeInterceptor;
import com.kanbancord_api.realtime.RealtimeOutboundInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final RealtimeProperties realtimeProperties;
    private final RealtimeHandshakeInterceptor realtimeHandshakeInterceptor;
    private final RealtimeHandshakeHandler realtimeHandshakeHandler;
    private final RealtimeChannelInterceptor realtimeChannelInterceptor;
    private final RealtimeOutboundInterceptor realtimeOutboundInterceptor;

    public WebSocketConfig(
            RealtimeProperties realtimeProperties,
            RealtimeHandshakeInterceptor realtimeHandshakeInterceptor,
            RealtimeHandshakeHandler realtimeHandshakeHandler,
            RealtimeChannelInterceptor realtimeChannelInterceptor,
            RealtimeOutboundInterceptor realtimeOutboundInterceptor) {
        this.realtimeProperties = realtimeProperties;
        this.realtimeHandshakeInterceptor = realtimeHandshakeInterceptor;
        this.realtimeHandshakeHandler = realtimeHandshakeHandler;
        this.realtimeChannelInterceptor = realtimeChannelInterceptor;
        this.realtimeOutboundInterceptor = realtimeOutboundInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setHandshakeHandler(realtimeHandshakeHandler)
                .addInterceptors(realtimeHandshakeInterceptor)
                .setAllowedOriginPatterns(realtimeProperties.getAllowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(realtimeChannelInterceptor);
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(realtimeOutboundInterceptor);
    }
}