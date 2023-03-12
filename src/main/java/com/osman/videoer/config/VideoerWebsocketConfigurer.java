package com.osman.videoer.config;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.osman.videoer.websocket.VideoEventHandler;

@Component
public class VideoerWebsocketConfigurer implements WebSocketConfigurer {

    @Resource
    private VideoEventHandler videoEventHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(videoEventHandler, "/ws").setAllowedOrigins("*");
        ;
    }
}