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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;

import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

abstract class AbstractScalaElement implements Element {

    private final String name;
    private final Object nativeType;
    private final Set<ElementModifier> modifiers;
    private final MutableAnnotationMetadata annotationMetadata;
    private final SimpleElementAnnotationMetadata elementAnnotationMetadata;

    AbstractScalaElement(
        String name,
        Object nativeType,
        Set<ElementModifier> modifiers,
        MutableAnnotationMetadata annotationMetadata,
        ScalaAnnotationMetadataBuilder annotationMetadataBuilder) {
        this.name = name;
        this.nativeType = nativeType;
        this.modifiers = modifiers == null ? Set.of() : Set.copyOf(modifiers);
        this.annotationMetadata = annotationMetadata == null ? new MutableAnnotationMetadata() : annotationMetadata;
        this.elementAnnotationMetadata = new SimpleElementAnnotationMetadata(this.annotationMetadata, false, annotationMetadataBuilder);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isProtected() {
        return modifiers.contains(ElementModifier.PROTECTED);
    }

    @Override
    public boolean isPublic() {
        return modifiers.contains(ElementModifier.PUBLIC) || (!isPrivate() && !isProtected());
    }

    @Override
    public boolean isPrivate() {
        return modifiers.contains(ElementModifier.PRIVATE);
    }

    @Override
    public boolean isAbstract() {
        return modifiers.contains(ElementModifier.ABSTRACT);
    }

    @Override
    public boolean isStatic() {
        return modifiers.contains(ElementModifier.STATIC);
    }

    @Override
    public boolean isFinal() {
        return modifiers.contains(ElementModifier.FINAL);
    }

    @Override
    public Set<ElementModifier> getModifiers() {
        return modifiers;
    }

    @Override
    public Object getNativeType() {
        return nativeType;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return elementAnnotationMetadata.getAnnotationMetadata();
    }

    protected SimpleElementAnnotationMetadata getElementAnnotationMetadata() {
        return elementAnnotationMetadata;
    }

    protected MutableAnnotationMetadataDelegate<?> getAnnotationMetadataToWrite() {
        return elementAnnotationMetadata;
    }

    @Override
    public <T extends Annotation> Element annotate(String annotationType, Consumer<AnnotationValueBuilder<T>> consumer) {
        getAnnotationMetadataToWrite().annotate(annotationType, consumer);
        return this;
    }

    @Override
    public <T extends Annotation> Element annotate(AnnotationValue<T> annotationValue) {
        getAnnotationMetadataToWrite().annotate(annotationValue);
        return this;
    }

    @Override
    public Element removeAnnotation(String annotationType) {
        getAnnotationMetadataToWrite().removeAnnotation(annotationType);
        return this;
    }

    @Override
    public <T extends Annotation> Element removeAnnotationIf(Predicate<AnnotationValue<T>> predicate) {
        getAnnotationMetadataToWrite().removeAnnotationIf(predicate);
        return this;
    }

    @Override
    public Element removeStereotype(String annotationType) {
        getAnnotationMetadataToWrite().removeStereotype(annotationType);
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbstractScalaElement that)) {
            return false;
        }
        return equalityType().equals(that.equalityType())
            && Objects.equals(equalityKey(), that.equalityKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(equalityType(), equalityKey());
    }

    protected Class<?> equalityType() {
        return getClass();
    }

    protected Object equalityKey() {
        return nativeType == null ? name : nativeType;
    }
}
