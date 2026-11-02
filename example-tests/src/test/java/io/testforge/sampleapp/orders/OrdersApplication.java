package io.testforge.sampleapp.orders;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

/**
 * A stand-in for the service an adapted project tests: a plain Spring MVC
 * application that declares nothing for Jackson and lets Spring Boot build its
 * {@code ObjectMapper}. Kept outside {@code io.testforge.example} so the
 * example suite's own application never scans it.
 */
@SpringBootApplication
public class OrdersApplication {

    public record Order(String item, int quantity) {
    }

    /** What the controller last received, exactly as it came off the wire. */
    public static final class Received {

        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();

        public String body() {
            return body.get();
        }

        public HttpHeaders headers() {
            return headers.get();
        }
    }

    @Bean
    Received received() {
        return new Received();
    }

    /** Keeps the raw bytes the message converter reads, so the controller can tell what was sent. */
    @Bean
    OncePerRequestFilter rawBodyCache() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(new ContentCachingRequestWrapper(request), response);
            }
        };
    }

    @RestController
    static class OrderController {

        private final Received received;

        OrderController(Received received) {
            this.received = received;
        }

        @PostMapping("/orders")
        Order create(@RequestBody Order order, @RequestHeader HttpHeaders headers, HttpServletRequest request) {
            // recorded before the response is written: the client may read the answer before a filter returns
            ContentCachingRequestWrapper raw = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
            received.body.set(raw == null ? null : new String(raw.getContentAsByteArray(), StandardCharsets.UTF_8));
            received.headers.set(headers);
            return order;
        }
    }
}
