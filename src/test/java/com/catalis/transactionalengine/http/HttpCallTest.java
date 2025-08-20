package com.catalis.transactionalengine.http;

import com.catalis.transactionalengine.core.SagaContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;
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
}
