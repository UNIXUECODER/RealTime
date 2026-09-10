package dev.realtime.config;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import dev.realtime.live.ChannelWebSocketHandler;

/**
 * Registers the raw WebSocketHandler at {@code /ws/{channelId}} — matched here as
 * {@code /ws/*} since {@link ChannelWebSocketHandler} parses channelId from the raw path
 * itself, rather than relying on Spring's path-variable extraction for WebSocketSession.
 */
@Configuration
public class WebSocketConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(ChannelWebSocketHandler channelWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/*", channelWebSocketHandler));
        mapping.setOrder(-1); // ahead of the default RequestMappingHandlerMapping
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
