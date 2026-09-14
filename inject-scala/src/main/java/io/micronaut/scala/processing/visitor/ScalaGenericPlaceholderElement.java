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
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
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
        this(typeData, visitorContext, placeholderMetadata(typeData, visitorContext));
    }

    private ScalaGenericPlaceholderElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(typeData, visitorContext, annotationMetadata);
        this.typeData = typeData;
        this.visitorContext = visitorContext;
    }

    /**
     * The annotations of a type variable: its bound's, with any of its own layered on top.
     *
     * <p>A type variable is erased to its bound, and the annotations that reach a bean
     * definition are the ones on the type that is actually there. Reading only the type
     * parameter's own annotations meant a generic parameter silently lost them:
     * {@code def save(book: MyBook)} carried {@code MyBook}'s {@code @Introspected} while
     * {@code def save[T <: MyBook](book: T)} carried nothing, and the same for a type
     * argument -- {@code List[MyBook]} against {@code List[T]}. The Java module resolves a
     * placeholder to its bound and the bound's metadata comes with it.</p>
     */
    private static AnnotationMetadata placeholderMetadata(ScalaTypeData typeData, ScalaVisitorContext visitorContext) {
        MutableAnnotationMetadata own = visitorContext.annotationMetadata(typeData);
        List<ScalaTypeData> bounds = typeData.bounds();
        if (bounds.isEmpty()) {
            return own;
        }
        ScalaTypeData bound = bounds.get(0);
        if (bound.primitive() || Object.class.getName().equals(bound.name())) {
            return own;
        }
        // `of` clones, so the cached metadata of the bound is not mutated here.
        MutableAnnotationMetadata combined = MutableAnnotationMetadata.of(visitorContext.annotationMetadata(bound));
        combined.addAnnotationMetadata(own);
        return combined;
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

    /**
     * A placeholder is identified by its variable, not by its erasure.
     *
     * <p>`ScalaClassElement` keys equality on {@code (name, arrayDimensions)}, and a
     * placeholder's name is its erasure, so for {@code class Repo[T, U]} the elements for
     * {@code T}, {@code U} and a plain {@code java.lang.Object} were all equal with the same
     * hash code. Micronaut caches elements aggressively, so distinct type arguments were
     * silently collapsing into one.
     */
    @Override
    protected Class<?> equalityType() {
        return ScalaGenericPlaceholderElement.class;
    }

    @Override
    protected Object equalityKey() {
        return new PlaceholderKey(getVariableName(), getName(), getArrayDimensions());
    }

    private record PlaceholderKey(String variableName, String erasedName, int arrayDimensions) {
    }
}
