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


package com.firefly.transactional.tools;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * Utilities to work with Java method references (Class::method) as serializable lambdas,
 * allowing extraction of the underlying java.lang.reflect.Method.
 *
 * We provide a small set of generic functional interfaces (Fn1..Fn4) that are Serializable
 * so that unbound instance method references like Orchestrator::c1 can be passed and inspected.
 */
public final class MethodRefs {
    private MethodRefs() {}

    // Serializable functional interfaces capable of capturing method references of various arities.
    public interface Fn1<A, R> extends Serializable { R apply(A a); }
    public interface Fn2<A, B, R> extends Serializable { R apply(A a, B b); }
    public interface Fn3<A, B, C, R> extends Serializable { R apply(A a, B b, C c); }
    public interface Fn4<A, B, C, D, R> extends Serializable { R apply(A a, B b, C c, D d); }

    /** Extract the java.lang.reflect.Method from a Serializable lambda created from a method reference. */
    public static Method methodOf(Serializable lambda) {
        SerializedLambda sl = serializedLambda(lambda);
        String implClass = sl.getImplClass().replace('/', '.');
        String methodName = sl.getImplMethodName();
        String descriptor = sl.getImplMethodSignature();
        Class<?> targetClass = classForName(implClass);
        Class<?>[] paramTypes = parseMethodDescriptorParameterTypes(descriptor, targetClass.getClassLoader());
        // Try direct lookup first
        try {
            Method m = targetClass.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            // Fallback: search by name and compatible parameter count
            Method found = null;
            for (Method m : targetClass.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (m.getParameterCount() != paramTypes.length) continue;
                if (isCompatible(paramTypes, m.getParameterTypes())) {
                    if (found != null) {
                        // ambiguous - prefer exact types later, but for now break and use exact-first logic above
                        // keep the first compatible and continue searching for exact
                    } else {
                        found = m;
                    }
                }
            }
            if (found != null) {
                found.setAccessible(true);
                return found;
            }
            throw new IllegalArgumentException("Could not resolve method reference: " + implClass + "::" + methodName + descriptor);
        }
    }

    private static boolean isCompatible(Class<?>[] wanted, Class<?>[] actual) {
        if (wanted.length != actual.length) return false;
        for (int i = 0; i < wanted.length; i++) {
            if (!wrap(actual[i]).isAssignableFrom(wrap(wanted[i]))) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == void.class) return Void.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }

    private static SerializedLambda serializedLambda(Serializable lambda) {
        try {
            Method writeReplace = lambda.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            Object replacement = writeReplace.invoke(lambda);
            if (replacement instanceof SerializedLambda sl) {
                return sl;
            }
            throw new IllegalArgumentException("Not a SerializedLambda: " + replacement);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Failed to extract SerializedLambda", e);
        }
    }

    private static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalArgumentException("Class not found: " + name, ex);
            }
        }
    }

    /**
     * Parse a JVM method descriptor and return parameter types as Classes.
     * Example: (Ljava/util/Map;Ljava/lang/String;)Lreactor/core/publisher/Mono;
     */
    private static Class<?>[] parseMethodDescriptorParameterTypes(String desc, ClassLoader cl) {
        int start = desc.indexOf('(');
        int end = desc.indexOf(')');
        if (start < 0 || end < 0 || end < start) throw new IllegalArgumentException("Bad method descriptor: " + desc);
        String params = desc.substring(start + 1, end);
        java.util.List<Class<?>> types = new java.util.ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            int arrayDims = 0;
            while (c == '[') { arrayDims++; i++; c = params.charAt(i); }
            Class<?> t;
            switch (c) {
                case 'B': t = byte.class; i++; break;
                case 'C': t = char.class; i++; break;
                case 'D': t = double.class; i++; break;
                case 'F': t = float.class; i++; break;
                case 'I': t = int.class; i++; break;
                case 'J': t = long.class; i++; break;
                case 'S': t = short.class; i++; break;
                case 'Z': t = boolean.class; i++; break;
                case 'V': t = void.class; i++; break;
                case 'L': {
                    int semi = params.indexOf(';', i);
                    String cname = params.substring(i + 1, semi).replace('/', '.');
                    t = forName(cname, cl);
                    i = semi + 1;
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown descriptor token '" + c + "' in " + desc);
            }
            if (arrayDims > 0) {
                StringBuilder arrName = new StringBuilder();
                for (int d = 0; d < arrayDims; d++) arrName.append('[');
                if (t.isPrimitive()) {
                    if (t == byte.class) arrName.append('B');
                    else if (t == char.class) arrName.append('C');
                    else if (t == double.class) arrName.append('D');
                    else if (t == float.class) arrName.append('F');
                    else if (t == int.class) arrName.append('I');
                    else if (t == long.class) arrName.append('J');
                    else if (t == short.class) arrName.append('S');
                    else if (t == boolean.class) arrName.append('Z');
                } else {
                    arrName.append('L').append(t.getName()).append(';');
                }
                try {
                    t = Class.forName(arrName.toString());
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Array class not found: " + arrName, e);
                }
            }
            types.add(t);
        }
        return types.toArray(new Class<?>[0]);
    }

    private static Class<?> forName(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ex) {
                throw new IllegalArgumentException("Class not found: " + name, ex);
            }
        }
    }
}
