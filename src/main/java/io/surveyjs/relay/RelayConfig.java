package io.surveyjs.relay;

import io.surveyjs.demo.SandboxContext;
import io.surveyjs.examples.EditTogetherRelay;
import io.surveyjs.examples.FillTogetherRelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * The relays run in this app, on its port: raw WebSocket handlers (no STOMP) at /ws/rooms/{recordId} and
 * /ws/forms/{formId}. Each relay is its own HandshakeInterceptor (its connection check). Spring's default origin check
 * stays: a page from another origin is refused, and behind a proxy the check compares the Origin with the forwarded
 * scheme and host. Never setAllowedOrigins("*").
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class RelayConfig implements WebSocketConfigurer {

    private static final int MAX_MESSAGE = 10 * 1024 * 1024;

    private final FillTogetherRelay fill;
    private final EditTogetherRelay edit;

    public RelayConfig(FillTogetherRelay fill, EditTogetherRelay edit) {
        this.fill = fill;
        this.edit = edit;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Demo only: SandboxContext carries the visitor's sandbox from the upgrade request to each message
        registry.addHandler(SandboxContext.around(fill), "/ws/rooms/{recordId}").addInterceptors(SandboxContext.handshake(), fill);
        registry.addHandler(SandboxContext.around(edit), "/ws/forms/{formId}").addInterceptors(SandboxContext.handshake(), edit);
    }

    /** Tomcat's default text buffer is 8 KB: a Creator record or a finished job sheet is larger, and 1009 would close the socket. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE);
        container.setMaxBinaryMessageBufferSize(MAX_MESSAGE);
        return container;
    }
}
