package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.annotations.CompensationSagaStep;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers and indexes Sagas from the Spring application context.
 * <p>
 * Responsibilities:
 * - Scan for beans annotated with {@link com.catalis.transactionalengine.annotations.Saga}.
 * - For each {@link com.catalis.transactionalengine.annotations.SagaStep} method, build a {@link StepDefinition}
 *   capturing configuration and resolve proxy-safe invocation methods.
 * - Resolve and attach compensation methods by name.
 * - Validate the declared step graph (dependencies exist, no cycles).
 *
 * Thread-safety: scanning runs once (idempotent) guarded by a volatile flag plus synchronized gate.
 */
public class SagaRegistry {

    private final ApplicationContext applicationContext;
    private final Map<String, SagaDefinition> sagas = new ConcurrentHashMap<>();
    private volatile boolean scanned = false;

    public SagaRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public SagaDefinition getSaga(String name) {
        ensureScanned();
        SagaDefinition def = sagas.get(name);
        if (def == null) {
            throw new IllegalArgumentException("Saga not found: " + name);
        }
        return def;
    }

    public Collection<SagaDefinition> getAll() {
        ensureScanned();
        return Collections.unmodifiableCollection(sagas.values());
    }

    private synchronized void ensureScanned() {
        if (scanned) return;
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Saga.class);
        // Track compensations that were declared by name but not found in-class; validate after external scan
        Set<String> missingInClassCompensations = new HashSet<>();
        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Saga sagaAnn = targetClass.getAnnotation(Saga.class);
            if (sagaAnn == null) continue; // safety
            String sagaName = sagaAnn.name();
            SagaDefinition sagaDef = new SagaDefinition(sagaName, bean, bean, sagaAnn.layerConcurrency());

            for (Method m : targetClass.getMethods()) {
                SagaStep stepAnn = m.getAnnotation(SagaStep.class);
                if (stepAnn == null) continue;
                Duration backoff = null;
                Duration timeout = null;
                // Prefer millis if provided for annotations; else consider legacy ISO-8601 strings; else defaults
                if (stepAnn.backoffMs() > 0) {
                    backoff = Duration.ofMillis(stepAnn.backoffMs());
                } else if (StringUtils.hasText(stepAnn.backoff())) {
                    try { backoff = Duration.parse(stepAnn.backoff()); } catch (Exception ignored) {}
                }
                if (stepAnn.timeoutMs() > 0) {
                    timeout = Duration.ofMillis(stepAnn.timeoutMs());
                } else if (StringUtils.hasText(stepAnn.timeout())) {
                    try { timeout = Duration.parse(stepAnn.timeout()); } catch (Exception ignored) {}
                }
                StepDefinition stepDef = new StepDefinition(
                        stepAnn.id(),
                        stepAnn.compensate(),
                        List.of(stepAnn.dependsOn()),
                        stepAnn.retry(),
                        backoff,
                        timeout,
                        stepAnn.idempotencyKey(),
                        stepAnn.jitter(),
                        stepAnn.jitterFactor(),
                        stepAnn.cpuBound(),
                        m
                );
                // Compensation-specific overrides
                if (stepAnn.compensationRetry() >= 0) stepDef.compensationRetry = stepAnn.compensationRetry();
                if (stepAnn.compensationBackoffMs() >= 0) stepDef.compensationBackoff = Duration.ofMillis(stepAnn.compensationBackoffMs());
                if (stepAnn.compensationTimeoutMs() >= 0) stepDef.compensationTimeout = Duration.ofMillis(stepAnn.compensationTimeoutMs());
                stepDef.compensationCritical = stepAnn.compensationCritical();
                // Resolve invocation method on the actual bean class (proxy-safe)
                stepDef.stepInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                if (sagaDef.steps.putIfAbsent(stepDef.id, stepDef) != null) {
                    throw new IllegalStateException("Duplicate step id '" + stepDef.id + "' in saga '" + sagaName + "'");
                }
            }

            // Resolve compensations (in-class first)
            for (StepDefinition sd : sagaDef.steps.values()) {
                if (!StringUtils.hasText(sd.compensateName)) continue;
                try {
                    Method comp = findCompensateMethod(targetClass, sd.compensateName);
                    sd.compensateMethod = comp;
                    sd.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), comp);
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException("Compensation method '" + sd.compensateName + "' not found in saga '" + sagaName + "'");
                }
            }

            validateDag(sagaDef);
            validateParameters(sagaDef);
            sagas.put(sagaName, sagaDef);
        }

        // Second pass: discover external steps and compensations
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);

        // 2.a External steps
        for (Object bean : allBeans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method m : targetClass.getMethods()) {
                com.catalis.transactionalengine.annotations.ExternalSagaStep es = m.getAnnotation(com.catalis.transactionalengine.annotations.ExternalSagaStep.class);
                if (es == null) continue;
                String sagaName = es.saga();
                if (!sagas.containsKey(sagaName)) {
                    throw new IllegalStateException("@ExternalSagaStep references unknown saga '" + sagaName + "'");
                }
                SagaDefinition def = sagas.get(sagaName);
                if (def.steps.containsKey(es.id())) {
                    throw new IllegalStateException("Duplicate step id '" + es.id() + "' in saga '" + sagaName + "' (external declaration)");
                }
                Duration backoff = null;
                Duration timeout = null;
                if (es.backoffMs() > 0) {
                    backoff = Duration.ofMillis(es.backoffMs());
                } else if (org.springframework.util.StringUtils.hasText(es.backoff())) {
                    try { backoff = Duration.parse(es.backoff()); } catch (Exception ignored) {}
                }
                if (es.timeoutMs() > 0) {
                    timeout = Duration.ofMillis(es.timeoutMs());
                } else if (org.springframework.util.StringUtils.hasText(es.timeout())) {
                    try { timeout = Duration.parse(es.timeout()); } catch (Exception ignored) {}
                }
                StepDefinition stepDef = new StepDefinition(
                        es.id(),
                        es.compensate(),
                        java.util.List.of(es.dependsOn()),
                        es.retry(),
                        backoff,
                        timeout,
                        es.idempotencyKey(),
                        es.jitter(),
                        es.jitterFactor(),
                        es.cpuBound(),
                        m
                );
                // Compensation-specific overrides
                if (es.compensationRetry() >= 0) stepDef.compensationRetry = es.compensationRetry();
                if (es.compensationBackoffMs() >= 0) stepDef.compensationBackoff = Duration.ofMillis(es.compensationBackoffMs());
                if (es.compensationTimeoutMs() >= 0) stepDef.compensationTimeout = Duration.ofMillis(es.compensationTimeoutMs());
                stepDef.compensationCritical = es.compensationCritical();

                stepDef.stepInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                stepDef.stepBean = bean;
                // If compensate() provided on same bean, attempt to resolve now; external @CompensationSagaStep can still override
                if (org.springframework.util.StringUtils.hasText(stepDef.compensateName)) {
                    try {
                        Method comp = findCompensateMethod(targetClass, stepDef.compensateName);
                        stepDef.compensateMethod = comp;
                        stepDef.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), comp);
                        stepDef.compensateBean = bean;
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException("Compensation method '" + stepDef.compensateName + "' not found on external step bean for saga '" + sagaName + "'");
                    }
                }
                def.steps.put(stepDef.id, stepDef);
            }
        }

        // 2.b External compensations and wire them with precedence
        // track duplicates
        Set<String> seenKeys = new HashSet<>();
        for (Object bean : allBeans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            for (Method m : targetClass.getMethods()) {
                CompensationSagaStep cs = m.getAnnotation(CompensationSagaStep.class);
                if (cs == null) continue;
                String sagaName = cs.saga();
                String stepId = cs.forStepId();
                String key = sagaName + "::" + stepId;
                if (!sagas.containsKey(sagaName)) {
                    throw new IllegalStateException("@CompensationSagaStep references unknown saga '" + sagaName + "' for step '" + stepId + "'");
                }
                SagaDefinition def = sagas.get(sagaName);
                StepDefinition sd = def.steps.get(stepId);
                if (sd == null) {
                    throw new IllegalStateException("@CompensationSagaStep references unknown step id '" + stepId + "' in saga '" + sagaName + "'");
                }
                if (!seenKeys.add(key)) {
                    throw new IllegalStateException("Duplicate @CompensationSagaStep mapping for saga '" + sagaName + "' step '" + stepId + "'");
                }
                // Override to external compensation
                sd.compensateMethod = m;
                sd.compensateInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                sd.compensateBean = bean;
            }
        }

        // Final validation after enriching with external steps/compensations
        for (SagaDefinition def : sagas.values()) {
            validateDag(def);
            validateParameters(def);
        }

        scanned = true;
    }

    private Method findCompensateMethod(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Method resolveInvocationMethod(Class<?> beanClass, Method targetMethod) {
        try {
            return beanClass.getMethod(targetMethod.getName(), targetMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            // Fallback: search by name and parameter count
            for (Method m : beanClass.getMethods()) {
                if (m.getName().equals(targetMethod.getName()) && m.getParameterCount() == targetMethod.getParameterCount()) {
                    return m;
                }
            }
            return targetMethod; // last resort
        }
    }

    private void validateDag(SagaDefinition saga) {
        // Ensure all dependsOn exist and no cycles via Kahn
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();
        for (String id : saga.steps.keySet()) {
            indegree.putIfAbsent(id, 0);
            adj.putIfAbsent(id, new ArrayList<>());
        }
        for (StepDefinition sd : saga.steps.values()) {
            for (String dep : sd.dependsOn) {
                if (!saga.steps.containsKey(dep)) {
                    throw new IllegalStateException("Step '" + sd.id + "' depends on missing step '" + dep + "'");
                }
                indegree.put(sd.id, indegree.getOrDefault(sd.id, 0) + 1);
                adj.get(dep).add(sd.id);
            }
        }
        Queue<String> q = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) if (e.getValue() == 0) q.add(e.getKey());
        int visited = 0;
        while (!q.isEmpty()) {
            String u = q.poll(); visited++;
            for (String v : adj.getOrDefault(u, List.of())) {
                indegree.put(v, indegree.get(v) - 1);
                if (indegree.get(v) == 0) q.add(v);
            }
        }
        if (visited != saga.steps.size()) {
            throw new IllegalStateException("Cycle detected in saga '" + saga.name + "'");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateParameters(SagaDefinition saga) {
        for (StepDefinition sd : saga.steps.values()) {
            // Validate against the original target method to preserve parameter annotations (proxy methods may not carry them)
            var method = sd.stepMethod;
            int implicitInputs = 0;
            var params = method.getParameters();
            for (int i = 0; i < params.length; i++) {
                var p = params[i];
                Class<?> type = p.getType();

                // Type-based resolvable: SagaContext
                if (com.catalis.transactionalengine.core.SagaContext.class.isAssignableFrom(type)) {
                    continue;
                }

                // Known annotations
                var in = p.getAnnotation(com.catalis.transactionalengine.annotations.Input.class);
                if (in != null) {
                    // no extra validation (keyed input may or may not exist at runtime)
                    continue;
                }
                var fs = p.getAnnotation(com.catalis.transactionalengine.annotations.FromStep.class);
                if (fs != null) {
                    String ref = fs.value();
                    if (!saga.steps.containsKey(ref)) {
                        throw new IllegalStateException("Step '" + sd.id + "' parameter #" + i + " references missing step '" + ref + "'");
                    }
                    continue;
                }
                var h = p.getAnnotation(com.catalis.transactionalengine.annotations.Header.class);
                if (h != null) {
                    // Ensure we can pass a String to this parameter
                    if (!type.isAssignableFrom(String.class)) {
                        throw new IllegalStateException("Step '" + sd.id + "' parameter #" + i + " @Header expects type assignable from String but was " + type.getName());
                    }
                    continue;
                }
                var hs = p.getAnnotation(com.catalis.transactionalengine.annotations.Headers.class);
                if (hs != null) {
                    if (!java.util.Map.class.isAssignableFrom(type)) {
                        throw new IllegalStateException("Step '" + sd.id + "' parameter #" + i + " @Headers expects a Map type but was " + type.getName());
                    }
                    continue;
                }
                var varAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variable.class);
                if (varAnn != null) {
                    // no static type validation; runtime variable value must be assignable to the parameter type
                    continue;
                }
                var varsAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variables.class);
                if (varsAnn != null) {
                    if (!java.util.Map.class.isAssignableFrom(type)) {
                        throw new IllegalStateException("Step '" + sd.id + "' parameter #" + i + " @Variables expects a Map type but was " + type.getName());
                    }
                    continue;
                }

                // Unannotated and not SagaContext -> implicit input
                implicitInputs++;
                if (implicitInputs > 1) {
                    throw new IllegalStateException("Step '" + sd.id + "' has more than one unannotated parameter; annotate with @Input/@FromStep/@Header/@Headers/@Variable/@Variables or use SagaContext");
                }
            }
        }
    }
}
