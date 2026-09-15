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
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import org.jspecify.annotations.Nullable;

/**
 * Scala field element.
 */
public final class ScalaFieldElement extends AbstractScalaMemberElement implements FieldElement {

    private final ScalaClassElement declaringType;
    private final ScalaVisitorContext visitorContext;
    private final ScalaFieldData fieldData;
    /** Whether this element carries metadata of its own rather than a view of its declaration's. */
    private final boolean presetAnnotationMetadata;
    private @Nullable ClassElement type;
    private @Nullable ClassElement genericType;

    ScalaFieldElement(ScalaClassElement declaringType, ScalaFieldData fieldData, ScalaVisitorContext visitorContext) {
        // The declaration's view of its own metadata: shared with every type the field is read
        // through until a bean property mutates it; see OwnerScopedAnnotationMetadata
        this(declaringType, fieldData, visitorContext, visitorContext.ownerScope(declaringType.getName(), fieldData).memberView());
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
        this.presetAnnotationMetadata = true;
    }

    /**
     * The field with the given view of its metadata: the view of the type it is read through,
     * or of the bean property it is a component of; see {@link OwnerScopedAnnotationMetadata}.
     *
     * @param declaringType The type declaring the field
     * @param fieldData The field
     * @param visitorContext The visitor context
     * @param elementAnnotationMetadata The view the field's mutations write through
     */
    ScalaFieldElement(
        ScalaClassElement declaringType,
        ScalaFieldData fieldData,
        ScalaVisitorContext visitorContext,
        ElementAnnotationMetadata elementAnnotationMetadata) {
        super(
            declaringType,
            fieldData.name(),
            fieldData.nativeType(),
            fieldData.modifiers(),
            elementAnnotationMetadata,
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.declaringType = declaringType;
        this.visitorContext = visitorContext;
        this.fieldData = fieldData;
        this.presetAnnotationMetadata = false;
    }

    /**
     * This field as a component of a bean property of the given type, through which a
     * mutation made on the property belongs to that type; an element given metadata of its own
     * is already what it stands for and is answered as is.
     *
     * @param owningType The type whose property the field is a component of
     * @return The field as a property component
     */
    FieldElement asPropertyComponentOf(ScalaClassElement owningType) {
        return presetAnnotationMetadata ? this : owningType.propertyFieldElement(declaringType, fieldData);
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
