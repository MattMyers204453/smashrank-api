package com.smashrank.smashrank_api.config;

import com.smashrank.smashrank_api.security.JwtUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-smashrank")
                .setAllowedOriginPatterns("*")
                .setHandshakeHandler(new UserHandshakeHandler(jwtUtil));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Phase 5: JWT-only connection mode.
     *
     *   ws://.../ws-smashrank?token=eyJhbG...
     *
     * Validates the token, extracts the username, and sets it as the Principal.
     * The legacy ?username= param is no longer accepted.
     */
    private static class UserHandshakeHandler extends DefaultHandshakeHandler {

        private final JwtUtil jwtUtil;

        UserHandshakeHandler(JwtUtil jwtUtil) {
            this.jwtUtil = jwtUtil;
        }

        @Override
        protected Principal determineUser(ServerHttpRequest request,
                                          WebSocketHandler wsHandler,
                                          Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                var httpRequest = servletRequest.getServletRequest();

                String token = httpRequest.getParameter("token");
                if (token != null && jwtUtil.isTokenValid(token)) {
                    String username = jwtUtil.extractUsername(token);
                    if (username != null) {
                        return () -> username;
                    }
                }
            }
            return null; // Reject connection — no valid JWT provided
        }
    }
}