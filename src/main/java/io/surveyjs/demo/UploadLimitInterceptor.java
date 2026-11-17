package io.surveyjs.demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Demo only: on the hosted demo (DEMO_MODE=true) a file over 5 MB answers 413 before any other check of the batch,
 * as the reference's middleware does. Off the demo, /api/files answers 422 and the scan 413, as the contract says.
 * The multipart request is already parsed when interceptors run. web/WebConfig adds it in demo mode only.
 */
public class UploadLimitInterceptor implements HandlerInterceptor {

    static final long MAX_BYTES = 5L * 1024 * 1024;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request instanceof MultipartHttpServletRequest multipart) {
            for (MultipartFile file : multipart.getMultiFileMap().values().stream().flatMap(java.util.List::stream).toList()) {
                if (file.getSize() > MAX_BYTES) {
                    throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Files over 5 MB are not accepted on the demo");
                }
            }
        }
        return true;
    }
}
