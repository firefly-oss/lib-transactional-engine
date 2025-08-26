package com.catalis.transactionalengine.http;

import com.catalis.transactionalengine.core.SagaContext;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Tiny helper for propagating correlation and headers into WebClient calls.
 * Usage:
 *   HttpCall.propagate(client.post().uri("/x").bodyValue(body), ctx).retrieve().bodyToMono(...)
 *
 * Plus convenience helpers to map error JSON bodies to domain exceptions to fail saga steps:
 *   return HttpCall.exchangeOrError(
 *       client.post().uri("/x").bodyValue(req), ctx,
 *       SuccessDto.class, ErrorDto.class,
 *       (status, err) -> new DownstreamException(status, err.code(), err.message())
 *   );
 */
public final class HttpCall {
    public static final String CORRELATION_HEADER = "X-Transactional-Id";

    private HttpCall() {}

    /**
     * Propagate correlation and headers from SagaContext into a WebClient request.
     */
    public static WebClient.RequestHeadersSpec<?> propagate(WebClient.RequestHeadersSpec<?> spec, SagaContext ctx) {
        if (ctx == null) return spec;
        WebClient.RequestHeadersSpec<?> s = spec.header(CORRELATION_HEADER, ctx.correlationId());
        for (Map.Entry<String, String> e : ctx.headers().entrySet()) {
            s = s.header(e.getKey(), e.getValue());
        }
        return s;
    }

    /**
     * Propagate correlation + headers and also add the provided extra headers for this call only.
     * If an extra header key is present in the context already, the extra value is appended
     * (WebClient#header semantics). To override, pass the same key with the desired value.
     */
    public static WebClient.RequestHeadersSpec<?> propagate(WebClient.RequestHeadersSpec<?> spec,
                                                            SagaContext ctx,
                                                            Map<String, String> extraHeaders) {
        WebClient.RequestHeadersSpec<?> s = propagate(spec, ctx);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    s = s.header(e.getKey(), e.getValue());
                }
            }
        }
        return s;
    }

    /**
     * Build Spring HttpHeaders with correlation and all custom headers from SagaContext.
     * Useful for clients other than WebClient (RestTemplate, Feign, OkHttp, etc.).
     */
    public static HttpHeaders buildHeaders(SagaContext ctx) {
        HttpHeaders h = new HttpHeaders();
        if (ctx == null) return h;
        h.add(CORRELATION_HEADER, ctx.correlationId());
        ctx.headers().forEach((k, v) -> { if (k != null && v != null) h.add(k, v); });
        return h;
    }

    /**
     * Executes the request, returning the success body on 2xx. For error statuses, tries to
     * deserialize the body to {@code errorType} and converts it to an exception via {@code errorMapper},
     * causing the resulting Mono to error (so a @SagaStep will fail and compensate).
     *
     * The {@code errorMapper} receives the numeric HTTP status and the parsed error body (which may be null
     * when the response has no body or it couldn't be parsed into {@code errorType}).
     */
    public static <T, E> Mono<T> exchangeOrError(
            WebClient.RequestHeadersSpec<?> spec,
            SagaContext ctx,
            Class<T> successType,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper
    ) {
        Objects.requireNonNull(successType, "successType");
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(errorMapper, "errorMapper");
        return propagate(spec, ctx)
                .exchangeToMono(resp -> handleResponse(resp, successType, errorType, errorMapper));
    }

    /**
     * Overload that ignores HTTP status in the mapper.
     */
    public static <T, E> Mono<T> exchangeOrError(
            WebClient.RequestHeadersSpec<?> spec,
            SagaContext ctx,
            Class<T> successType,
            Class<E> errorType,
            Function<E, ? extends Throwable> errorMapper
    ) {
        return exchangeOrError(spec, ctx, successType, errorType, (status, e) -> errorMapper.apply(e));
    }

    /**
     * Convenience for calls that do not return a body (204/empty). On non-2xx, maps the error body.
     */
    public static <E> Mono<Void> exchangeVoidOrError(
            WebClient.RequestHeadersSpec<?> spec,
            SagaContext ctx,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper
    ) {
        return exchangeOrError(spec, ctx, Void.class, errorType, errorMapper).then();
    }

    private static <T, E> Mono<T> handleResponse(
            ClientResponse resp,
            Class<T> successType,
            Class<E> errorType,
            BiFunction<Integer, E, ? extends Throwable> errorMapper
    ) {
        if (resp.statusCode().is2xxSuccessful()) {
            return resp.bodyToMono(successType);
        }
        int status = resp.statusCode().value();
        return resp.bodyToMono(errorType)
                .map(java.util.Optional::of)
                .onErrorResume(ex -> Mono.just(java.util.Optional.empty())) // parsing failures -> pass null to mapper
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(opt -> Mono.error(errorMapper.apply(status, opt.orElse(null))));
    }
}
