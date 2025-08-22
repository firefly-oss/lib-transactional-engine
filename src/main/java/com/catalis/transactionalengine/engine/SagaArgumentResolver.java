package com.catalis.transactionalengine.engine;

import com.catalis.transactionalengine.core.SagaContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracted helper responsible for resolving method arguments for saga step invocations.
 * It compiles and caches parameter resolvers to avoid repeated reflection work.
 */
final class SagaArgumentResolver {

    @FunctionalInterface
    private interface ArgResolver {
        Object resolve(Object input, SagaContext ctx);
    }

    private ArgResolver wrapRequired(java.lang.reflect.Parameter p, Method method, ArgResolver base) {
        var req = p.getAnnotation(com.catalis.transactionalengine.annotations.Required.class);
        if (req == null) return base;
        String paramName = p.getName();
        int index = -1; // we don't have direct index here; name and method should suffice
        return (in, c) -> {
            Object v = base.resolve(in, c);
            if (v == null) {
                throw new IllegalStateException("Required parameter '" + paramName + "' in method " + method + " resolved to null");
            }
            return v;
        };
    }

    private final Map<Method, ArgResolver[]> argResolverCache = new ConcurrentHashMap<>();

    Object[] resolveArguments(Method method, Object input, SagaContext ctx) {
        ArgResolver[] resolvers = argResolverCache.computeIfAbsent(method, this::compileArgResolvers);
        Object[] args = new Object[resolvers.length];
        for (int i = 0; i < resolvers.length; i++) {
            args[i] = resolvers[i].resolve(input, ctx);
        }
        return args;
    }

    private ArgResolver[] compileArgResolvers(Method method) {
        var params = method.getParameters();
        if (params.length == 0) return new ArgResolver[0];
        ArgResolver[] resolvers = new ArgResolver[params.length];
        boolean implicitUsed = false;
        for (int i = 0; i < params.length; i++) {
            var p = params[i];
            Class<?> type = p.getType();

            if (SagaContext.class.isAssignableFrom(type)) {
                resolvers[i] = wrapRequired(p, method, (in, c) -> c);
                continue;
            }

            var inputAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Input.class);
            if (inputAnn != null) {
                String key = inputAnn.value();
                if (key == null || key.isBlank()) {
                    resolvers[i] = wrapRequired(p, method, (in, c) -> in);
                } else {
                    resolvers[i] = wrapRequired(p, method, (in, c) -> (in instanceof Map<?, ?> m) ? m.get(key) : null);
                }
                continue;
            }

            var fromStepAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.FromStep.class);
            if (fromStepAnn != null) {
                String ref = fromStepAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.getResult(ref));
                continue;
            }

            var fromCompResAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.FromCompensationResult.class);
            if (fromCompResAnn != null) {
                String ref = fromCompResAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.getCompensationResult(ref));
                continue;
            }

            var compErrAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.CompensationError.class);
            if (compErrAnn != null) {
                String ref = compErrAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.getCompensationError(ref));
                continue;
            }

            var headerAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Header.class);
            if (headerAnn != null) {
                String name = headerAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.headers().get(name));
                continue;
            }

            var headersAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Headers.class);
            if (headersAnn != null) {
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.headers());
                continue;
            }

            var variableAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variable.class);
            if (variableAnn != null) {
                String name = variableAnn.value();
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.getVariable(name));
                continue;
            }

            var variablesAnn = p.getAnnotation(com.catalis.transactionalengine.annotations.Variables.class);
            if (variablesAnn != null) {
                resolvers[i] = wrapRequired(p, method, (in, c) -> c.variables());
                continue;
            }

            if (!implicitUsed) {
                resolvers[i] = wrapRequired(p, method, (in, c) -> in);
                implicitUsed = true;
            } else {
                String msg = "Unresolvable parameter '" + p.getName() + "' at position " + i +
                        " in method " + method + ". Use @Input/@FromStep/@FromCompensationResult/@CompensationError/@Header/@Headers/@Variable/@Variables or SagaContext.";
                throw new IllegalStateException(msg);
            }
        }
        return resolvers;
    }

}
