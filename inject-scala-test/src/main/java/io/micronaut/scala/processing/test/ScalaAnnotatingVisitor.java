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

import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.function.Supplier;

/**
 * Test visitor used to verify that Scala elements can be annotated by visitors.
 */
public final class ScalaAnnotatingVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * Annotation name used by the visitor.
     */
    public static final String ANN = "foo.bar.ScalaVisitorAnn";

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();
    private static final ThreadLocal<String> CLASS_ANNOTATION = new ThreadLocal<>();
    private static final ThreadLocal<String> METHOD_ANNOTATION = new ThreadLocal<>();
    private static final ThreadLocal<RuntimeException> START_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<RuntimeException> CLASS_FAILURE = new ThreadLocal<>();
    private static final ThreadLocal<RuntimeException> FINISH_FAILURE = new ThreadLocal<>();

    /**
     * Executes a compilation with this visitor enabled.
     *
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withAnnotations(Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            ENABLED.remove();
        }
    }

    /**
     * Executes a compilation with this visitor adding the given annotation to classes.
     *
     * @param annotationName The annotation name
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withClassAnnotation(String annotationName, Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        CLASS_ANNOTATION.set(annotationName);
        try {
            return supplier.get();
        } finally {
            CLASS_ANNOTATION.remove();
            ENABLED.remove();
        }
    }

    /**
     * Executes a compilation with this visitor adding the given annotation to methods.
     *
     * @param annotationName The annotation name
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withMethodAnnotation(String annotationName, Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        METHOD_ANNOTATION.set(annotationName);
        try {
            return supplier.get();
        } finally {
            METHOD_ANNOTATION.remove();
            ENABLED.remove();
        }
    }

    /**
     * Executes a compilation with this visitor failing when visitor processing starts.
     *
     * @param exception The exception to throw
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withStartFailure(RuntimeException exception, Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        START_FAILURE.set(exception);
        try {
            return supplier.get();
        } finally {
            START_FAILURE.remove();
            ENABLED.remove();
        }
    }

    /**
     * Executes a compilation with this visitor failing when it visits a class.
     *
     * @param exception The exception to throw
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withClassFailure(RuntimeException exception, Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        CLASS_FAILURE.set(exception);
        try {
            return supplier.get();
        } finally {
            CLASS_FAILURE.remove();
            ENABLED.remove();
        }
    }

    /**
     * Executes a compilation with this visitor failing when visitor processing finishes.
     *
     * @param exception The exception to throw
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withFinishFailure(RuntimeException exception, Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        FINISH_FAILURE.set(exception);
        try {
            return supplier.get();
        } finally {
            FINISH_FAILURE.remove();
            ENABLED.remove();
        }
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        if (!enabled()) {
            return;
        }
        RuntimeException startFailure = START_FAILURE.get();
        if (startFailure != null) {
            throw startFailure;
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!enabled()) {
            return;
        }
        RuntimeException classFailure = CLASS_FAILURE.get();
        if (classFailure != null) {
            throw classFailure;
        }
        annotate(element, "class");
        String classAnnotation = CLASS_ANNOTATION.get();
        if (classAnnotation != null) {
            element.annotate(classAnnotation);
        }
        for (PropertyElement propertyElement : element.getBeanProperties()) {
            annotate(propertyElement, "property");
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (!enabled()) {
            return;
        }
        annotate(element, "method");
        String methodAnnotation = METHOD_ANNOTATION.get();
        if (methodAnnotation != null) {
            element.annotate(methodAnnotation);
        }
        for (ParameterElement parameter : element.getParameters()) {
            annotate(parameter, "parameter");
        }
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        if (!enabled()) {
            return;
        }
        RuntimeException finishFailure = FINISH_FAILURE.get();
        if (finishFailure != null) {
            throw finishFailure;
        }
    }

    private static boolean enabled() {
        return Boolean.TRUE.equals(ENABLED.get());
    }

    private static void annotate(io.micronaut.inject.ast.Element element, String target) {
        element.annotate(ANN, (AnnotationValueBuilder<Annotation> builder) -> builder.member("target", target));
    }
}
