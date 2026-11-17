package io.surveyjs.web;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The same error shape on every platform: under {@code /api} every error is JSON {@code { error }} with its HTTP
 * status, including Spring's own answers, which would otherwise be {@code {timestamp, status, error, path}}.
 * Outside {@code /api} a short HTML page says what went wrong ({@link ErrorPages}).
 *
 * <p>Handlers signal errors the usual Spring way: {@code throw new ResponseStatusException(HttpStatus.NOT_FOUND,
 * "Unknown form")}, or return {@code ResponseEntity.status(…).body(Map.of("error", …))}. What never reaches a
 * handler (Tomcat's own errors, the firewall) goes through {@link ErrorPages}.
 */
@RestControllerAdvice
public class ApiErrors {

    private static final Log log = LogFactory.getLog(ApiErrors.class);

    static final String EDIT_FORMS = "You don't have editor rights: only editors can change forms";
    static final String NOT_JSON = "The request body is not valid JSON";
    static final String TOO_LARGE = "The request body is too large";

    /** The contract's wording for a plain status. */
    static String message(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 413 -> TOO_LARGE;
            case 415 -> "Unsupported media type";
            case 422 -> "Unprocessable content";
            case 429 -> "Too many requests";
            case 500 -> "Internal server error";
            default -> {
                HttpStatus known = HttpStatus.resolve(status);
                yield known == null ? "Error" : known.getReasonPhrase();
            }
        };
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> statusError(ResponseStatusException e, HttpServletRequest request) {
        String reason = e.getReason();
        return answer(request, e.getStatusCode(), e.getHeaders(), reason == null || reason.isBlank() ? message(e.getStatusCode().value()) : reason);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        // Allow lists every method the path answers, from every controller that maps it
        return answer(request, e.getStatusCode(), e.getHeaders(), message(405));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Object> unsupportedType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return answer(request, e.getStatusCode(), new HttpHeaders(), "Send the body as application/json");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        return answer(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), "Missing " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> wrongType(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return answer(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), "Invalid " + e.getName() + ": " + e.getValue());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> notJson(HttpMessageNotReadableException e, HttpServletRequest request) {
        if (causedBy(e, BodyLimitFilter.TooLarge.class)) {   // a chunked body over the limit
            return answer(request, HttpStatus.CONTENT_TOO_LARGE, new HttpHeaders(), TOO_LARGE);
        }
        return answer(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), NOT_JSON);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Object> uploadTooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return answer(request, HttpStatus.CONTENT_TOO_LARGE, new HttpHeaders(), TOO_LARGE);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> notMultipart(MultipartException e, HttpServletRequest request) {
        if (causedBy(e, BodyLimitFilter.TooLarge.class)) {
            return answer(request, HttpStatus.CONTENT_TOO_LARGE, new HttpHeaders(), TOO_LARGE);
        }
        return answer(request, HttpStatus.BAD_REQUEST, new HttpHeaders(), "Send the files as multipart/form-data");
    }

    /** {@code @PreAuthorize("hasAuthority('edit-forms')")} refused: Bob, and signed-out visitors alike. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> accessDenied(AccessDeniedException e, HttpServletRequest request) {
        return answer(request, HttpStatus.FORBIDDEN, new HttpHeaders(), EDIT_FORMS);
    }

    /**
     * Anything a handler didn't expect: logged with its stack trace, answered without details. Spring's other
     * ErrorResponse exceptions (404 for an unknown path, 406, …) keep their status.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse error) {
            return answer(request, error.getStatusCode(), error.getHeaders(), message(error.getStatusCode().value()));
        }
        log.error("Unhandled error on " + request.getMethod() + " " + request.getRequestURI(), e);
        return answer(request, HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(), message(500));
    }

    private static ResponseEntity<Object> answer(HttpServletRequest request, HttpStatusCode status, HttpHeaders headers, String message) {
        if (!request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(status).headers(headers).contentType(MediaType.TEXT_HTML)
                    .body(ErrorPages.page(status.value(), message));
        }
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
