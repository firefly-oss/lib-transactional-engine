package com.catalis.transactionalengine.registry;

import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
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
        for (Object bean : beans.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            Saga sagaAnn = targetClass.getAnnotation(Saga.class);
            if (sagaAnn == null) continue; // safety
            String sagaName = sagaAnn.name();
            SagaDefinition sagaDef = new SagaDefinition(sagaName, bean, bean);

            for (Method m : targetClass.getMethods()) {
                SagaStep stepAnn = m.getAnnotation(SagaStep.class);
                if (stepAnn == null) continue;
                StepDefinition stepDef = new StepDefinition(
                        stepAnn.id(),
                        stepAnn.compensate(),
                        List.of(stepAnn.dependsOn()),
                        stepAnn.retry(),
                        stepAnn.backoffMs(),
                        stepAnn.timeoutMs(),
                        stepAnn.idempotencyKey(),
                        m
                );
                // Resolve invocation method on the actual bean class (proxy-safe)
                stepDef.stepInvocationMethod = resolveInvocationMethod(bean.getClass(), m);
                if (sagaDef.steps.putIfAbsent(stepDef.id, stepDef) != null) {
                    throw new IllegalStateException("Duplicate step id '" + stepDef.id + "' in saga '" + sagaName + "'");
                }
            }

            // Resolve compensations
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

                // Unannotated and not SagaContext -> implicit input
                implicitInputs++;
                if (implicitInputs > 1) {
                    throw new IllegalStateException("Step '" + sd.id + "' has more than one unannotated parameter; annotate with @Input/@FromStep/@Header/@Headers or use SagaContext");
                }
            }
        }
    }
}
