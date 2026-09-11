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
import io.micronaut.inject.ast.FieldElement;
import org.jspecify.annotations.Nullable;

/**
 * Scala field element.
 */
public final class ScalaFieldElement extends AbstractScalaMemberElement implements FieldElement {

    private final ScalaClassElement declaringType;
    private final ScalaVisitorContext visitorContext;
    private final ScalaFieldData fieldData;
    private @Nullable ClassElement type;
    private @Nullable ClassElement genericType;

    ScalaFieldElement(ScalaClassElement declaringType, ScalaFieldData fieldData, ScalaVisitorContext visitorContext) {
        this(declaringType, fieldData, visitorContext, visitorContext.annotationMetadata(fieldData));
    }

    private ScalaFieldElement(
        ScalaClassElement declaringType,
        ScalaFieldData fieldData,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata) {
        super(
            declaringType,
            fieldData.name(),
            fieldData.nativeType(),
            fieldData.modifiers(),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.declaringType = declaringType;
        this.visitorContext = visitorContext;
        this.fieldData = fieldData;
    }

    @Override
    public ClassElement getType() {
        if (type == null) {
            type = visitorContext.getElementFactory().newClassElement(fieldData.type());
        }
        return type;
    }

    /**
     * The type with the declaring type's variables bound as they were where the field was
     * reached; {@link #getType()} stays the declared type, the JVM signature.
     */
    @Override
    public ClassElement getGenericType() {
        if (fieldData.genericType() == null) {
            return getType();
        }
        if (genericType == null) {
            genericType = visitorContext.getElementFactory().newClassElement(fieldData.resolvedType());
        }
        return genericType;
    }

    @Override
    public @Nullable Object getConstantValue() {
        return fieldData.constantValue();
    }

    @Override
    public boolean isReflectionRequired() {
        return isReflectionRequired(declaringType);
    }

    @Override
    public boolean isReflectionRequired(ClassElement callingType) {
        // Answering true unconditionally -- and ignoring the calling type entirely -- made
        // Micronaut emit reflective access and GraalVM reflection metadata for every field,
        // including ones it could read directly.
        return !isAccessible(callingType);
    }

    @Override
    public FieldElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaFieldElement(declaringType, fieldData, visitorContext, annotationMetadata);
    }
}
