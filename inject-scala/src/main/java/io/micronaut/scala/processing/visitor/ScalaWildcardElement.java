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
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;

import java.util.List;

/**
 * Scala wildcard element backed by compiler type bounds.
 */
final class ScalaWildcardElement extends ScalaClassElement implements WildcardElement {

    private final ScalaTypeData typeData;
    private final ScalaVisitorContext visitorContext;

    ScalaWildcardElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext) {
        this(typeData, visitorContext, visitorContext.annotationMetadata(typeData));
    }

    private ScalaWildcardElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(typeData, visitorContext, annotationMetadata);
        this.typeData = typeData;
        this.visitorContext = visitorContext;
    }

    @Override
    public List<? extends ClassElement> getUpperBounds() {
        return typeData.upperBounds().stream()
            .map(visitorContext.getElementFactory()::newClassElement)
            .toList();
    }

    @Override
    public List<? extends ClassElement> getLowerBounds() {
        return typeData.lowerBounds().stream()
            .map(visitorContext.getElementFactory()::newClassElement)
            .toList();
    }

    @Override
    public boolean isBounded() {
        return !getLowerBounds().isEmpty() || WildcardElement.super.isBounded();
    }

    @Override
    public MutableAnnotationMetadataDelegate<AnnotationMetadata> getGenericTypeAnnotationMetadata() {
        return getElementAnnotationMetadata();
    }

    /**
     * Copying must keep the element a wildcard. Without this the inherited copy returned a
     * plain {@code ScalaClassElement}, so every {@code instanceof WildcardElement} check in
     * the generics writers failed on a copied element -- and copying is what annotating one
     * does.
     *
     * <p>An *array* of a wildcard is the exception, and is not a wildcard itself.
     * `Array[_]` is modelled as one type carrying both the wildcard bounds and the array
     * dimension, so a copy of that must erase to an ordinary array class element --
     * `Array[_]` is `Object[]`, not a wildcard.
     */
    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        if (getArrayDimensions() > 0) {
            return super.withAnnotationMetadata(annotationMetadata);
        }
        return new ScalaWildcardElement(typeData, visitorContext, annotationMetadata);
    }

    /**
     * Two wildcards are the same only when their bounds are, so they cannot share the
     * erasure-keyed equality of an ordinary class element.
     */
    @Override
    protected Class<?> equalityType() {
        return ScalaWildcardElement.class;
    }

    @Override
    protected Object equalityKey() {
        return new WildcardKey(
            getName(),
            getArrayDimensions(),
            typeData.upperBounds().stream().map(ScalaTypeData::name).toList(),
            typeData.lowerBounds().stream().map(ScalaTypeData::name).toList()
        );
    }

    private record WildcardKey(String name, int arrayDimensions, List<String> upperBounds, List<String> lowerBounds) {
    }
}
