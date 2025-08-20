package com.catalis.transactionalengine.http;

import com.catalis.transactionalengine.core.SagaContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Tiny helper for propagating correlation and headers into WebClient calls.
 * Usage:
 *   HttpCall.propagate(client.post().uri("/x").bodyValue(body), ctx).retrieve().bodyToMono(...)
 */
public final class HttpCall {
    public static final String CORRELATION_HEADER = "X-Transactional-Id";

    private HttpCall() {}

    public static WebClient.RequestHeadersSpec<?> propagate(WebClient.RequestHeadersSpec<?> spec, SagaContext ctx) {
        if (ctx == null) return spec;
        WebClient.RequestHeadersSpec<?> s = spec.header(CORRELATION_HEADER, ctx.correlationId());
        for (Map.Entry<String, String> e : ctx.headers().entrySet()) {
            s = s.header(e.getKey(), e.getValue());
        }
        return s;
    }
}
