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


package com.firefly.transactionalengine.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SagaContextTest {

    @Test
    void headersAndCorrelationAndResults() {
        SagaContext ctx = new SagaContext("corr-123");
        assertEquals("corr-123", ctx.correlationId());
        ctx.putHeader("user", "u1");
        assertEquals("u1", ctx.headers().get("user"));

        assertNull(ctx.getResult("a"));
        ctx.putResult("a", "ok");
        assertEquals("ok", ctx.getResult("a"));

        assertNull(ctx.getStatus("a"));
        ctx.setStatus("a", StepStatus.RUNNING);
        assertEquals(StepStatus.RUNNING, ctx.getStatus("a"));

        assertEquals(0, ctx.getAttempts("a"));
        ctx.incrementAttempts("a");
        ctx.incrementAttempts("a");
        assertEquals(2, ctx.getAttempts("a"));

        assertEquals(0L, ctx.getLatency("a"));
        ctx.setLatency("a", 42L);
        assertEquals(42L, ctx.getLatency("a"));

        Map<String, Object> view = ctx.stepResultsView();
        assertThrows(UnsupportedOperationException.class, () -> view.put("x", 1));
    }

    @Test
    void idempotencyKeys() {
        SagaContext ctx = new SagaContext();
        assertFalse(ctx.hasIdempotencyKey("k1"));
        assertTrue(ctx.markIdempotent("k1"));
        assertTrue(ctx.hasIdempotencyKey("k1"));
        // adding again returns false (already present)
        assertFalse(ctx.markIdempotent("k1"));
    }
}
