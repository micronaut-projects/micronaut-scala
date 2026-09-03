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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Scala generic placeholder element backed by compiler symbols.
 */
final class ScalaGenericPlaceholderElement extends ScalaClassElement implements GenericPlaceholderElement {

    private final ScalaTypeData typeData;
    private final ScalaVisitorContext visitorContext;

    ScalaGenericPlaceholderElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext) {
        this(typeData, visitorContext, visitorContext.annotationMetadata(typeData));
    }

    private ScalaGenericPlaceholderElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(typeData, visitorContext, annotationMetadata);
        this.typeData = typeData;
        this.visitorContext = visitorContext;
    }

    @Override
    public boolean isTypeVariable() {
        return true;
    }

    @Override
    public List<? extends ClassElement> getBounds() {
        return typeData.bounds().stream()
            .map(visitorContext.getElementFactory()::newClassElement)
            .toList();
    }

    @Override
    public String getVariableName() {
        return Objects.requireNonNull(typeData.variableName());
    }

    @Override
    public Optional<Element> getDeclaringElement() {
        return Optional.empty();
    }

    @Override
    public ClassElement withArrayDimensions(int arrayDimensions) {
        if (arrayDimensions == getArrayDimensions()) {
            return this;
        }
        return new ScalaGenericPlaceholderElement(typeData.withArrayDimensions(arrayDimensions), visitorContext, getAnnotationMetadata());
    }

    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaGenericPlaceholderElement(typeData, visitorContext, annotationMetadata);
    }

    @Override
    public MutableAnnotationMetadataDelegate<AnnotationMetadata> getGenericTypeAnnotationMetadata() {
        return getElementAnnotationMetadata();
    }
}
