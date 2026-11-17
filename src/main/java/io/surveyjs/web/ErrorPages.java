package io.surveyjs.web;

import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * The {@code /error} page, in place of Boot's {@code BasicErrorController}: what never reached a handler (Tomcat's own
 * errors, Spring Security's filters, the firewall's 404) ends here. Under {@code /api} it answers {@code { error }};
 * elsewhere a short HTML page. Handler errors go through {@link ApiErrors} instead.
 */
@RestController
public class ErrorPages implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<Object> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer number ? number : HttpStatus.INTERNAL_SERVER_ERROR.value();
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String message = ApiErrors.message(status);
        if (uri instanceof String path && path.startsWith("/api/")) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("error", message));
        }
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(page(status, message));
    }

    static String page(int status, String message) {
        return "<!DOCTYPE html><html lang=\"en\"><meta charset=\"utf-8\"><title>" + status + "</title><p>"
                + HtmlUtils.htmlEscape(message) + "</p></html>";
    }
}
