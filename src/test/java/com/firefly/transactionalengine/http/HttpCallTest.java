/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.firefly.transactionalengine.http;

import com.firefly.transactionalengine.core.SagaContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpCallTest {

    @Test
    void propagatesCorrelationAndCustomHeaders() {
        // Given
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);

        SagaContext ctx = new SagaContext("corr-xyz");
        ctx.putHeader("X-User", "u1");
        ctx.putHeader("X-Tenant", "t1");

        // When
        WebClient.RequestHeadersSpec<?> out = HttpCall.propagate(spec, ctx);

        // Then: returns the same spec for chaining
        assertSame(spec, out);

        // Verify headers added
        verify(spec).header(HttpCall.CORRELATION_HEADER, "corr-xyz");
        verify(spec).header("X-User", "u1");
        verify(spec).header("X-Tenant", "t1");
        verifyNoMoreInteractions(spec);
    }

    @Test
    void nullContextIsNoop() {
        WebClient.RequestHeadersSpec<?> spec = mock(WebClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
        WebClient.RequestHeadersSpec<?> out = HttpCall.propagate(spec, null);
        assertSame(spec, out);
        verifyNoInteractions(spec);
    }

    @Test
    void propagateWithExtraHeadersMerges() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);

        SagaContext ctx = new SagaContext("c-1");
        ctx.putHeader("X-User", "u1");

        Map<String,String> extra = Map.of("X-Request-Id", "r-1", "X-User", "u1b");

        WebClient.RequestHeadersSpec<?> out = HttpCall.propagate(spec, ctx, extra);
        assertSame(spec, out);

        // order not strictly guaranteed; verify each call occurred
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-1");
        verify(spec).header("X-User", "u1");
        verify(spec).header("X-Request-Id", "r-1");
        verify(spec).header("X-User", "u1b"); // appended
        verifyNoMoreInteractions(spec);
    }

    @Test
    void propagateWithExtraHeadersIgnoresNulls() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        SagaContext ctx = new SagaContext("c-2");

        Map<String,String> extra = new HashMap<>();
        extra.put("X-A", "a");
        extra.put(null, "x");
        extra.put("X-B", null);

        HttpCall.propagate(spec, ctx, extra);
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-2");
        verify(spec).header("X-A", "a");
        verifyNoMoreInteractions(spec);
    }

    @Test
    void buildHeadersProducesCorrelationAndCustom() {
        SagaContext ctx = new SagaContext("c-abc");
        ctx.putHeader("X-User", "u-1");
        ctx.putHeader("X-Tenant", "t-1");

        HttpHeaders h = HttpCall.buildHeaders(ctx);
        assertEquals("c-abc", h.getFirst(HttpCall.CORRELATION_HEADER));
        assertEquals("u-1", h.getFirst("X-User"));
        assertEquals("t-1", h.getFirst("X-Tenant"));
    }

    @Test
    void buildHeadersNullContextIsEmpty() {
        HttpHeaders h = HttpCall.buildHeaders(null);
        assertNull(h.getFirst(HttpCall.CORRELATION_HEADER));
        assertTrue(h.isEmpty());
    }

    // --- exchangeOrError and exchangeVoidOrError

    record SuccessDto(String id) {}
    record ErrorDto(String code, String message) {}

    @Test
    void exchangeOrError_successReturnsBody() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);

        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.exchangeToMono(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<ClientResponse, Mono<SuccessDto>> fn = (Function<ClientResponse, Mono<SuccessDto>>) inv.getArgument(0);
            ClientResponse resp = mock(ClientResponse.class);
            when(resp.statusCode()).thenReturn(HttpStatus.OK);
            when(resp.bodyToMono(SuccessDto.class)).thenReturn(Mono.just(new SuccessDto("ok")));
            return fn.apply(resp);
        });

        SagaContext ctx = new SagaContext("c-ok");

        SuccessDto out = HttpCall.exchangeOrError(spec, ctx, SuccessDto.class, ErrorDto.class,
                (status, e) -> new IllegalStateException("status=" + status)).block();

        assertNotNull(out);
        assertEquals("ok", out.id());
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-ok");
    }

    @Test
    void exchangeOrError_non2xxMapsParsedErrorBody() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.exchangeToMono(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<ClientResponse, Mono<SuccessDto>> fn = (Function<ClientResponse, Mono<SuccessDto>>) inv.getArgument(0);
            ClientResponse resp = mock(ClientResponse.class);
            when(resp.statusCode()).thenReturn(HttpStatus.BAD_REQUEST);
            when(resp.bodyToMono(ErrorDto.class)).thenReturn(Mono.just(new ErrorDto("E1", "bad")));
            return fn.apply(resp);
        });

        SagaContext ctx = new SagaContext("c-err");

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            HttpCall.exchangeOrError(spec, ctx, SuccessDto.class, ErrorDto.class,
                (status, e) -> new RuntimeException("mapped:" + status + "," + (e == null ? "null" : e.code())))
                .block()
        );
        assertTrue(ex.getMessage().contains("mapped:400,E1"));
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-err");
    }

    @Test
    void exchangeOrError_errorWithUnparseableBodyPassesNullToMapper() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.exchangeToMono(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<ClientResponse, Mono<SuccessDto>> fn = (Function<ClientResponse, Mono<SuccessDto>>) inv.getArgument(0);
            ClientResponse resp = mock(ClientResponse.class);
            when(resp.statusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
            when(resp.bodyToMono(ErrorDto.class)).thenReturn(Mono.error(new RuntimeException("parse error")));
            return fn.apply(resp);
        });

        SagaContext ctx = new SagaContext("c-err2");

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            HttpCall.exchangeOrError(spec, ctx, SuccessDto.class, ErrorDto.class,
                (status, e) -> new RuntimeException("mapped:" + status + "," + (e == null ? "null" : e.code())))
                .block()
        );
        assertTrue(ex.getMessage().contains("mapped:500,null"));
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-err2");
    }

    @Test
    void exchangeVoidOrError_successCompletes() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.exchangeToMono(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<ClientResponse, Mono<Void>> fn = (Function<ClientResponse, Mono<Void>>) inv.getArgument(0);
            ClientResponse resp = mock(ClientResponse.class);
            when(resp.statusCode()).thenReturn(HttpStatus.NO_CONTENT);
            when(resp.bodyToMono(Void.class)).thenReturn(Mono.empty());
            return fn.apply(resp);
        });

        SagaContext ctx = new SagaContext("c-v1");
        assertDoesNotThrow(() -> HttpCall.exchangeVoidOrError(spec, ctx, ErrorDto.class,
            (status, e) -> new RuntimeException("e")).block());
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-v1");
    }

    @Test
    void exchangeVoidOrError_non2xxMapsError() {
        WebClient.RequestBodyUriSpec spec = mock(WebClient.RequestBodyUriSpec.class);
        when(spec.header(anyString(), anyString())).thenReturn(spec);
        when(spec.exchangeToMono(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Function<ClientResponse, Mono<Void>> fn = (Function<ClientResponse, Mono<Void>>) inv.getArgument(0);
            ClientResponse resp = mock(ClientResponse.class);
            when(resp.statusCode()).thenReturn(HttpStatus.CONFLICT);
            when(resp.bodyToMono(ErrorDto.class)).thenReturn(Mono.just(new ErrorDto("E2", "conflict")));
            return fn.apply(resp);
        });

        SagaContext ctx = new SagaContext("c-v2");
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            HttpCall.exchangeVoidOrError(spec, ctx, ErrorDto.class,
                (status, e) -> new RuntimeException("status=" + status + ", code=" + (e == null ? "null" : e.code())))
                .block()
        );
        assertTrue(ex.getMessage().contains("status=409, code=E2"));
        verify(spec).header(HttpCall.CORRELATION_HEADER, "c-v2");
    }
}
