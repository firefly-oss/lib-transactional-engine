package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.engine.StepHandler;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Immutable metadata for a single Saga step extracted from annotations or built programmatically.
 * Contains configuration knobs (retry/backoff/timeout/idempotencyKey), wiring to
 * the discovered step method and its proxy-safe invocation counterpart, and the
 * optional compensation method pair. Alternatively, a functional {@link StepHandler}
 * can be provided to execute the step without reflection.
 */
public class StepDefinition {
    public final String id;
    public final String compensateName;
    public final List<String> dependsOn;
    public final int retry;
    public final long backoffMs;
    public final long timeoutMs;
    public final String idempotencyKey;
    public final boolean jitter;
    public final double jitterFactor;
    public final Method stepMethod; // method discovered on target class (for metadata)
    public Method stepInvocationMethod; // method to invoke on the bean (proxy-safe)
    public Method compensateMethod; // discovered on target class (for metadata)
    public Method compensateInvocationMethod; // method to invoke on the bean (proxy-safe)
    // Functional execution alternative
    public StepHandler<?,?> handler;

    public StepDefinition(String id,
                          String compensateName,
                          List<String> dependsOn,
                          int retry,
                          long backoffMs,
                          long timeoutMs,
                          String idempotencyKey,
                          boolean jitter,
                          double jitterFactor,
                          Method stepMethod) {
        this.id = id;
        this.compensateName = compensateName;
        this.dependsOn = dependsOn;
        this.retry = retry;
        this.backoffMs = backoffMs;
        this.timeoutMs = timeoutMs;
        this.idempotencyKey = idempotencyKey;
        this.jitter = jitter;
        this.jitterFactor = jitterFactor;
        this.stepMethod = stepMethod;
    }
}
