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
package io.micronaut.scala.processing.visitor;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementModifier;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Scala parameter element.
 */
public final class ScalaParameterElement extends AbstractScalaElement implements ParameterElement {

    private final MethodElement methodElement;
    private final ScalaParameterData parameterData;
    private final ScalaVisitorContext visitorContext;
    @Nullable
    private ClassElement type;

    ScalaParameterElement(MethodElement methodElement, ScalaParameterData parameterData, ScalaVisitorContext visitorContext) {
        this(methodElement, parameterData, visitorContext, visitorContext.annotationMetadata(parameterData));
    }

    private ScalaParameterElement(
        MethodElement methodElement,
        ScalaParameterData parameterData,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata) {
        super(
            parameterData.name(),
            parameterData.nativeType(),
            Set.of(ElementModifier.PUBLIC),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.methodElement = methodElement;
        this.parameterData = parameterData;
        this.visitorContext = visitorContext;
    }

    @Override
    public ClassElement getType() {
        if (type == null) {
            type = visitorContext.getElementFactory().newClassElement(parameterData.type());
        }
        return type;
    }

    @Override
    public MethodElement getMethodElement() {
        return methodElement;
    }

    @Override
    public ParameterElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaParameterElement(methodElement, parameterData, visitorContext, annotationMetadata);
    }

    @Override
    protected Object equalityKey() {
        return new ParameterElementKey(methodElement, parameterData.name());
    }

    private record ParameterElementKey(MethodElement methodElement, String name) {
    }
}
