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
package io.micronaut.scala.processing.test;

import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.function.Supplier;

/**
 * Test visitor used to register evaluated-expression context classes.
 */
public final class ScalaExpressionContextRegistrar implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<List<String>> CONTEXT_CLASSES = new ThreadLocal<>();

    /**
     * Executes a compilation with the given expression context classes registered.
     *
     * @param contextClasses The context class names
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withContextClasses(List<String> contextClasses, Supplier<T> supplier) {
        CONTEXT_CLASSES.set(contextClasses);
        try {
            return supplier.get();
        } finally {
            CONTEXT_CLASSES.remove();
        }
    }

    @Override
    public void start(VisitorContext visitorContext) {
        List<String> contextClasses = CONTEXT_CLASSES.get();
        if (contextClasses == null) {
            return;
        }
        for (String contextClass : contextClasses) {
            visitorContext.getClassElement(contextClass).ifPresent(classElement ->
                visitorContext.getExpressionCompilationContextFactory().registerContextClass(classElement)
            );
        }
    }

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }
}
