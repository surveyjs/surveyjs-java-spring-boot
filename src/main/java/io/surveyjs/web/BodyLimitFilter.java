package io.surveyjs.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A cap on request bodies. Tomcat's maxPostSize covers form posts only, so JSON bodies have no limit of their own.
 *
 * <p>24 MB fits the largest body this app accepts: a 5 MB scan, base64-encoded for the SurveyJS service, arrives
 * here as multipart, and a Creator definition with images is a few MB. The handlers keep their own 5 MB rules, and
 * multipart has the same limit (spring.servlet.multipart.*). A body that says it's larger is refused at once; a
 * chunked one stops being read when it passes the limit.
 */
public class BodyLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY = 24L * 1024 * 1024;

    /** Thrown when a body without Content-Length passes the limit: ApiErrors answers 413. */
    public static class TooLarge extends IOException {
        TooLarge() {
            super(ApiErrors.TOO_LARGE);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Connection", "close");
            response.getWriter().write("{\"error\":\"" + ApiErrors.TOO_LARGE + "\"}");
            return;
        }
        chain.doFilter(request.getContentLengthLong() < 0 ? new Limited(request) : request, response);
    }

    /** A request whose body stops at MAX_BODY bytes. */
    private static final class Limited extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new Counting(super.getInputStream());
            }
            return stream;
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream in;
        private long read;

        Counting(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws TooLarge {
            read += n;
            if (read > MAX_BODY) {
                throw new TooLarge();
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
