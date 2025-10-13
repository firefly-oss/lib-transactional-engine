/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.transactional.tcc.engine;

import com.firefly.transactional.tcc.annotations.FromTry;
import com.firefly.transactional.tcc.annotations.Header;
import com.firefly.transactional.tcc.annotations.Input;
import com.firefly.transactional.tcc.core.TccContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCC-specific argument resolver for resolving method parameters in TCC participant methods.
 * <p>
 * This resolver supports the following parameter types and annotations:
 * <ul>
 *   <li>{@link TccContext} - The current TCC context</li>
 *   <li>{@link Input @Input} - Input data for the participant</li>
 *   <li>{@link Header @Header} - Header values from the TCC context</li>
 *   <li>{@link FromTry @FromTry} - Results from the try phase (for confirm/cancel methods)</li>
 *   <li>Implicit input parameter (first parameter without annotation)</li>
 * </ul>
 * <p>
 * This is a TCC-specific implementation that replaces the usage of {@code SagaArgumentResolver}
 * to ensure complete isolation between SAGA and TCC patterns.
 */
public class TccArgumentResolver {
    
    private static final Logger log = LoggerFactory.getLogger(TccArgumentResolver.class);
    
    private final Map<Method, ArgResolver[]> argResolverCache = new ConcurrentHashMap<>();
    
    /**
     * Resolves arguments for a TCC participant method.
     *
     * @param method the method to resolve arguments for
     * @param input the input data
     * @param context the TCC context
     * @return the resolved arguments array
     */
    public Object[] resolveArguments(Method method, Object input, TccContext context) {
        return resolveArguments(method, input, context, null);
    }

    /**
     * Resolves arguments for a TCC participant method with specific try result.
     *
     * @param method the method to resolve arguments for
     * @param input the input data
     * @param context the TCC context
     * @param participantTryResult the specific try result for this participant (for confirm/cancel phases)
     * @return the resolved arguments array
     */
    public Object[] resolveArguments(Method method, Object input, TccContext context, Object participantTryResult) {
        ArgResolver[] resolvers = argResolverCache.computeIfAbsent(method, this::compileArgResolvers);
        Object[] args = new Object[resolvers.length];
        for (int i = 0; i < resolvers.length; i++) {
            args[i] = resolvers[i].resolve(input, context, participantTryResult);
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
            
            // TccContext parameter
            if (TccContext.class.isAssignableFrom(type)) {
                resolvers[i] = wrapRequired(p, method, (in, ctx, tryResult) -> ctx);
                continue;
            }
            
            // @Input annotation
            Input inputAnnotation = p.getAnnotation(Input.class);
            if (inputAnnotation != null) {
                String key = inputAnnotation.value().isEmpty() ? null : inputAnnotation.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx, tryResult) -> {
                    if (key == null) {
                        return in;
                    } else if (in instanceof Map<?, ?> map) {
                        return map.get(key);
                    } else {
                        log.warn("@Input with key '{}' used but input is not a Map: {}", key, in);
                        return null;
                    }
                });
                continue;
            }
            
            // @Header annotation
            Header headerAnnotation = p.getAnnotation(Header.class);
            if (headerAnnotation != null) {
                String headerName = headerAnnotation.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx, tryResult) -> {
                    return ctx.getHeader(headerName);
                });
                continue;
            }
            
            // @FromTry annotation
            FromTry fromTryAnnotation = p.getAnnotation(FromTry.class);
            if (fromTryAnnotation != null) {
                String fieldName = fromTryAnnotation.value();
                resolvers[i] = wrapRequired(p, method, (in, ctx, tryResult) -> {
                    // If no field name specified, return the entire try result for the current participant
                    if (fieldName.isEmpty()) {
                        // Return the participant-specific try result passed from the invoker
                        return tryResult;
                    } else {
                        // Return specific field from try result (if it's a complex object)
                        // For now, just return the try result as-is since field extraction is complex
                        return tryResult;
                    }
                });
                continue;
            }
            
            // Implicit input parameter (first parameter without annotation)
            if (!implicitUsed) {
                resolvers[i] = wrapRequired(p, method, (in, ctx, tryResult) -> in);
                implicitUsed = true;
            } else {
                String msg = "Unresolvable parameter '" + p.getName() + "' of type " + type.getSimpleName() + 
                           " in method " + method.getDeclaringClass().getSimpleName() + "." + method.getName() + 
                           ". Consider adding @Input, @Header, @FromTry annotation or making it a TccContext parameter.";
                throw new IllegalArgumentException(msg);
            }
        }
        
        return resolvers;
    }
    
    private ArgResolver wrapRequired(Parameter p, Method method, ArgResolver resolver) {
        if (isRequired(p)) {
            return (input, context, participantTryResult) -> {
                Object result = resolver.resolve(input, context, participantTryResult);
                if (result == null) {
                    String msg = "Required parameter '" + p.getName() + "' in method " +
                               method.getDeclaringClass().getSimpleName() + "." + method.getName() +
                               " resolved to null";
                    throw new IllegalArgumentException(msg);
                }
                return result;
            };
        }
        return resolver;
    }
    
    private boolean isRequired(Parameter p) {
        // Check for @Input annotation with required flag
        Input input = p.getAnnotation(Input.class);
        if (input != null) {
            return input.required();
        }
        
        // Check for @Header annotation with required flag
        Header header = p.getAnnotation(Header.class);
        if (header != null) {
            return header.required();
        }
        
        // Check for @FromTry annotation (always required for now)
        FromTry fromTry = p.getAnnotation(FromTry.class);
        if (fromTry != null) {
            return true; // FromTry parameters are always required
        }
        
        // TccContext and implicit parameters are always required
        return TccContext.class.isAssignableFrom(p.getType()) || 
               (p.getAnnotations().length == 0);
    }
    
    @FunctionalInterface
    private interface ArgResolver {
        Object resolve(Object input, TccContext context, Object participantTryResult);
    }
}
