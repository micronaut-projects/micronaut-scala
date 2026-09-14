/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.scala.processing.fixtures;

import io.micronaut.core.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shapes of a compiled Java class that the compiler's own reading of a class file leaves
 * out, and the plugin therefore reads from the class file: annotations on parameters with
 * every kind of value, type annotations on returns and fields, private members with generic
 * signatures, constants, an inner class whose constructor takes the outer instance, and a
 * static nested class.
 */
public class ExternalJavaShapes {

    /** A constant, whose value the class file records. */
    public static final String GREETING = "hello";

    /** A constant of a primitive type. */
    public static final int LIMIT = 7;

    private final Map<String, List<Integer>> index = Map.of();

    @ExternalTagged(weight = 1)
    private String tagged;

    private static int counter;

    /** A field whose type carries a type annotation. */
    public @Nullable String label;

    /**
     * Every kind of annotation value on parameters.
     *
     * @param level an enum value
     * @param type a class value
     * @param names an array value
     * @param note a nested annotation value
     * @param plain a parameter without annotations
     */
    public ExternalJavaShapes(
        @ExternalTagged(weight = 1, level = ExternalLevel.HIGH) String level,
        @ExternalTagged(weight = 2, type = List.class) String type,
        @ExternalTagged(weight = 3, names = {"a", "b"}) String names,
        @ExternalTagged(weight = 4, note = @ExternalNote("inner")) String note,
        String plain) {
    }

    /**
     * A return type carrying a type annotation, with a generic parameter.
     *
     * @param values the values
     * @return the first
     */
    public @Nullable String first(List<? extends CharSequence> values) {
        return values.isEmpty() ? null : values.get(0).toString();
    }

    /**
     * A method type parameter and an annotated generic parameter.
     *
     * @param value the value
     * @param <T> the value type
     * @return the value wrapped
     */
    public <T extends Comparable<T>> Optional<T> wrap(@ExternalTagged(weight = 5) T value) {
        return Optional.of(value);
    }

    private <K> Map<K, List<Integer>> privateGeneric(K key, @ExternalTagged(weight = 6) int count) {
        return Map.of();
    }

    private static void privateStatic() {
    }

    /** An inner class, whose constructor takes the enclosing instance. */
    public class Inner {

        private final int n;

        /**
         * @param n the number
         * @param tag an optional tag
         */
        public Inner(@ExternalTagged(weight = 8) int n, @Nullable String tag) {
            this.n = n;
        }

        /** @return the number */
        public int n() {
            return n;
        }
    }

    /** A static nested class. */
    public static class Nested {

        /** @return the enclosing class's greeting */
        public String greeting() {
            return GREETING;
        }
    }
}
