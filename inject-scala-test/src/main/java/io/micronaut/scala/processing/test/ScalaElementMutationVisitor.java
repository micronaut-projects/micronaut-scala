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

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Test visitor used to verify mutation parity for Scala elements.
 */
public final class ScalaElementMutationVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * Annotation name used by the visitor.
     */
    public static final String ANN = "foo.bar.ScalaMutationAnn";

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();

    /**
     * Executes a compilation with this visitor enabled.
     *
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withMutations(Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        try {
            return supplier.get();
        } finally {
            ENABLED.remove();
        }
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!enabled()) {
            return;
        }
        annotate(element, "class");
        element.annotate(Requires.class, builder -> builder.member("env", "test"));
        element.annotate(Requires.class, builder -> builder.member("property", "feature.enabled"));
        for (FieldElement field : element.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
            annotate(field, "field");
            annotate(field.getType(), "field-type");
            annotateTypeArguments(field.getType());
        }
        for (PropertyElement property : element.getBeanProperties()) {
            annotate(property, "property");
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (!enabled()) {
            return;
        }
        annotate(element, "method");
        annotate(element.getReturnType(), "return-type");
        annotateTypeArguments(element.getReturnType());
        for (ParameterElement parameter : element.getParameters()) {
            annotate(parameter, "parameter");
            annotate(parameter.getType(), "parameter-type");
            annotateTypeArguments(parameter.getType());
        }
    }

    private static boolean enabled() {
        return Boolean.TRUE.equals(ENABLED.get());
    }

    private static void annotate(Element element, String target) {
        if (element instanceof PrimitiveElement) {
            return;
        }
        element.annotate(ANN, (AnnotationValueBuilder<Annotation> builder) -> builder
            .member("target", target)
            .member("values", new String[0]));
    }

    private static void annotateTypeArguments(ClassElement element) {
        for (Map.Entry<String, ClassElement> entry : element.getTypeArguments().entrySet()) {
            annotate(entry.getValue(), "type-argument");
            annotateTypeArguments(entry.getValue());
        }
    }
}
