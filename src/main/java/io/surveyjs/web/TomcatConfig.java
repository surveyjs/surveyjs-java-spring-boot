package io.surveyjs.web;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Tomcat answers a path with {@code %2f} (an encoded slash, as in {@code /shared/..%2f.env}) with its own HTML 400.
 * Passing it through lets Spring Security's firewall reject it like every other traversal path, with the 404 the
 * contract asks for (security/SecurityConfig). Boot has no property for this connector setting.
 */
@Component
public class TomcatConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling("passthrough"));
    }
}
