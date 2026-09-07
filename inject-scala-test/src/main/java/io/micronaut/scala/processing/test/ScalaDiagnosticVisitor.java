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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

/**
 * Test visitor that reports a diagnostic against a chosen element, so a spec can assert
 * where the compiler attributed it. {@code VisitorContext} is the only way a visitor can
 * report anything, and the element it is given is the only thing that can carry a source
 * position, so this is the seam the position plumbing has to be tested through.
 */
public final class ScalaDiagnosticVisitor implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    /**
     * The diagnostic severity to report.
     */
    public enum Severity {
        /**
         * Report through {@code VisitorContext.info}.
         */
        INFO,
        /**
         * Report through {@code VisitorContext.warn}.
         */
        WARN,
        /**
         * Report through {@code VisitorContext.fail}, which also throws.
         */
        FAIL
    }

    /**
     * Which element of the visited class to blame.
     */
    public enum Target {
        /**
         * The class itself.
         */
        CLASS,
        /**
         * The first declared method.
         */
        METHOD,
        /**
         * The first declared field.
         */
        FIELD,
        /**
         * No element at all, so the diagnostic has nothing to point at.
         */
        NONE
    }

    private record Request(Severity severity, Target target, String message) {
    }

    /**
     * Runs a compilation with this visitor reporting the given diagnostic.
     *
     * @param severity The severity
     * @param target The element to blame
     * @param message The message
     * @param runnable The compilation
     */
    public static void reporting(Severity severity, Target target, String message, Runnable runnable) {
        REQUEST.set(new Request(severity, target, message));
        try {
            runnable.run();
        } finally {
            REQUEST.remove();
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        Request request = REQUEST.get();
        if (request == null) {
            return;
        }
        // One report per compilation: fail() throws, and repeating a warning across
        // several visited classes would make the assertions order-dependent.
        REQUEST.remove();
        Element target = resolveTarget(element, request.target());
        switch (request.severity()) {
            case INFO -> context.info(request.message(), target);
            case WARN -> context.warn(request.message(), target);
            case FAIL -> context.fail(request.message(), target);
            default -> throw new IllegalStateException("Unknown severity " + request.severity());
        }
    }

    private static @Nullable Element resolveTarget(ClassElement element, Target target) {
        return switch (target) {
            case CLASS -> element;
            case METHOD -> element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS.onlyDeclared())
                .stream().map(MethodElement.class::cast).findFirst().orElse(null);
            case FIELD -> element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_FIELDS.onlyDeclared())
                .stream().map(FieldElement.class::cast).findFirst().orElse(null);
            case NONE -> null;
        };
    }
}
