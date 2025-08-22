package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.engine.StepHandler;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

/**
 * Immutable metadata for a single Saga step extracted from annotations or built programmatically.
 * Contains configuration knobs (retry/backoff/timeout/idempotencyKey), wiring to
 * the discovered step method and its proxy-safe invocation counterpart, and the
 * optional compensation method pair. Alternatively, a functional {@link StepHandler}
 * can be provided to execute the step without reflection.
 */
public class StepDefinition {
    public static final Duration DEFAULT_BACKOFF = Duration.ofMillis(100);
    public static final Duration DEFAULT_TIMEOUT = Duration.ZERO; // 0 disables timeout to avoid breaking existing behavior

    public final String id;
    public final String compensateName;
    public final List<String> dependsOn;
    public final int retry;
    public final Duration backoff;
    public final Duration timeout;
    public final String idempotencyKey;
    public final boolean jitter;
    public final double jitterFactor;
    public final boolean cpuBound;
    public final Method stepMethod; // method discovered on target class (for metadata)
    public Method stepInvocationMethod; // method to invoke on the bean (proxy-safe)
    /** Optional: when step is declared externally, this holds the target bean to invoke on. */
    public Object stepBean;
    public Method compensateMethod; // discovered on target class (for metadata)
    public Method compensateInvocationMethod; // method to invoke on the bean (proxy-safe)
    /** Optional: when compensation is declared externally, this holds the target bean to invoke on. */
    public Object compensateBean;
    // Functional execution alternative
    public StepHandler<?,?> handler;

    // Compensation-specific optional configuration (null means inherit from step config)
    public Integer compensationRetry; // -1 or null means inherit
    public Duration compensationBackoff; // null means inherit
    public Duration compensationTimeout; // null means inherit
    public boolean compensationCritical; // default false

    // Optional event publication configuration
    public StepEventConfig stepEvent;

    public StepDefinition(String id,
                          String compensateName,
                          List<String> dependsOn,
                          int retry,
                          Duration backoff,
                          Duration timeout,
                          String idempotencyKey,
                          boolean jitter,
                          double jitterFactor,
                          boolean cpuBound,
                          Method stepMethod) {
        this.id = id;
        this.compensateName = compensateName;
        this.dependsOn = dependsOn;
        this.retry = retry;
        this.backoff = backoff != null ? backoff : DEFAULT_BACKOFF;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
        this.idempotencyKey = idempotencyKey;
        this.jitter = jitter;
        this.jitterFactor = jitterFactor;
        this.cpuBound = cpuBound;
        this.stepMethod = stepMethod;
    }
}
