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
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.ast.annotation.PropertyElementAnnotationMetadata;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scala property element.
 */
public final class ScalaPropertyElement extends AbstractScalaMemberElement implements PropertyElement {

    private static final String VALUE_ANNOTATION = "io.micronaut.context.annotation.Value";

    private final ScalaClassElement declaringType;
    private final ScalaVisitorContext visitorContext;
    @Nullable
    private final ScalaPropertyData propertyData;
    private final ClassElement type;
    @Nullable
    private final MethodElement readMethod;
    @Nullable
    private final MethodElement writeMethod;
    @Nullable
    private final FieldElement field;
    private final AccessKind readAccessKind;
    private final AccessKind writeAccessKind;
    private final boolean excluded;
    private final ElementAnnotationMetadata annotationMetadata;

    ScalaPropertyElement(ScalaClassElement declaringType, ScalaPropertyData propertyData, ScalaVisitorContext visitorContext) {
        this(declaringType, propertyData, visitorContext, null);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private ScalaPropertyElement(
        ScalaClassElement declaringType,
        ScalaPropertyData propertyData,
        ScalaVisitorContext visitorContext,
        @Nullable
        AnnotationMetadata annotationMetadata) {
        this(
            declaringType,
            propertyData,
            visitorContext.getElementFactory().newClassElement(propertyData.type()),
            propertyData.name(),
            propertyData.readMethod() == null ? null : declaringType.methodElement(propertyData.readMethod()),
            propertyData.writeMethod() == null ? null : declaringType.methodElement(propertyData.writeMethod()),
            propertyData.field() == null ? null : declaringType.fieldElement(propertyData.field()),
            AccessKind.METHOD,
            AccessKind.METHOD,
            false,
            propertyData.nativeType(),
            propertyData.modifiers(),
            visitorContext.annotationMetadata(propertyData),
            annotationMetadata,
            visitorContext
        );
    }

    ScalaPropertyElement(
        ScalaClassElement declaringType,
        ClassElement type,
        String name,
        @Nullable MethodElement readMethod,
        @Nullable MethodElement writeMethod,
        @Nullable FieldElement field,
        AccessKind readAccessKind,
        AccessKind writeAccessKind,
        boolean excluded,
        ScalaVisitorContext visitorContext) {
        this(
            declaringType,
            null,
            type,
            name,
            readMethod,
            writeMethod,
            field,
            readAccessKind,
            writeAccessKind,
            excluded,
            selectNativeType(readMethod, writeMethod, field),
            selectModifiers(readMethod, writeMethod, field),
            AnnotationMetadata.EMPTY_METADATA,
            null,
            visitorContext
        );
    }

    private ScalaPropertyElement(
        ScalaClassElement declaringType,
        @Nullable ScalaPropertyData propertyData,
        ClassElement type,
        String name,
        @Nullable MethodElement readMethod,
        @Nullable MethodElement writeMethod,
        @Nullable FieldElement field,
        AccessKind readAccessKind,
        AccessKind writeAccessKind,
        boolean excluded,
        Object nativeType,
        Set<io.micronaut.inject.ast.ElementModifier> modifiers,
        AnnotationMetadata baseAnnotationMetadata,
        @Nullable AnnotationMetadata presetAnnotationMetadata,
        ScalaVisitorContext visitorContext) {
        super(
            declaringType,
            name,
            nativeType,
            modifiers,
            MutableAnnotationMetadata.of(baseAnnotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.declaringType = declaringType;
        this.visitorContext = visitorContext;
        this.propertyData = propertyData;
        this.type = type;
        this.readMethod = readMethod;
        this.writeMethod = writeMethod;
        this.field = field;
        this.readAccessKind = readAccessKind;
        this.writeAccessKind = writeAccessKind;
        this.excluded = excluded;
        this.annotationMetadata = presetAnnotationMetadata == null
            ? new PropertyElementAnnotationMetadata(
                this,
                readMethod,
                writeMethod,
                field,
                null,
                baseAnnotationMetadata,
                true
            )
            : new SimpleElementAnnotationMetadata(MutableAnnotationMetadata.of(presetAnnotationMetadata), false, visitorContext.getScalaAnnotationMetadataBuilder());
    }

    @Override
    public ClassElement getType() {
        return type;
    }

    @Override
    public Optional<FieldElement> getField() {
        return Optional.ofNullable(field);
    }

    @Override
    public Optional<MethodElement> getWriteMethod() {
        return Optional.ofNullable(writeMethod);
    }

    @Override
    public Optional<MethodElement> getReadMethod() {
        return Optional.ofNullable(readMethod);
    }

    @Override
    public Optional<? extends MemberElement> getReadMember() {
        if (readAccessKind == AccessKind.FIELD) {
            return getField();
        }
        return getReadMethod()
            .map(methodElement -> methodElement.withAnnotationMetadata(readMemberAnnotationMetadata()));
    }

    @Override
    public Optional<? extends MemberElement> getWriteMember() {
        if (writeAccessKind == AccessKind.FIELD) {
            return getField().filter(fieldElement -> !fieldElement.isFinal());
        }
        if (writeMethod != null) {
            return getWriteMethod()
                .map(this::writeMember);
        }
        return PropertyElement.super.getWriteMember();
    }

    private MethodElement writeMember(MethodElement methodElement) {
        MethodElement writeMember = methodElement.withAnnotationMetadata(writeMemberAnnotationMetadata());
        return writeMemberParameters(writeMember)
            .map(writeMember::withParameters)
            .orElse(writeMember);
    }

    private Optional<ParameterElement[]> writeMemberParameters(MethodElement methodElement) {
        ParameterElement[] parameters = methodElement.getParameters();
        if (parameters.length != 1 || field == null) {
            return Optional.empty();
        }
        AnnotationMetadata qualifierAnnotationMetadata = qualifierAnnotationMetadata(field.getAnnotationMetadata());
        if (qualifierAnnotationMetadata.isEmpty()) {
            return Optional.empty();
        }
        ParameterElement[] newParameters = parameters.clone();
        ParameterElement parameter = parameters[0];
        AnnotationMetadata annotationMetadata = new AnnotationMetadataHierarchy(
            parameter,
            qualifierAnnotationMetadata
        ).merge();
        newParameters[0] = parameter.withAnnotationMetadata(annotationMetadata);
        return Optional.of(newParameters);
    }

    private AnnotationMetadata qualifierAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        List<AnnotationValue<Annotation>> qualifierValues = annotationMetadata.getAnnotationValuesByStereotype(AnnotationUtil.QUALIFIER);
        if (qualifierValues.isEmpty()) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        boolean optionalInjection = !annotationMetadata.booleanValue(AnnotationUtil.INJECT, AnnotationUtil.MEMBER_REQUIRED)
            .orElse(true);
        MutableAnnotationMetadata qualifierMetadata = new MutableAnnotationMetadata();
        for (AnnotationValue<Annotation> qualifierValue : qualifierValues) {
            String annotationName = qualifierValue.getAnnotationName();
            if (optionalInjection && VALUE_ANNOTATION.equals(annotationName)) {
                continue;
            }
            qualifierMetadata.addDeclaredAnnotation(annotationName, qualifierValue.getValues(), qualifierValue.getRetentionPolicy());
            qualifierMetadata.addDeclaredStereotype(
                List.of(annotationName),
                AnnotationUtil.QUALIFIER,
                Map.of(),
                qualifierValue.getRetentionPolicy()
            );
        }
        return qualifierMetadata.isEmpty() ? AnnotationMetadata.EMPTY_METADATA : qualifierMetadata;
    }

    private AnnotationMetadata readMemberAnnotationMetadata() {
        if (propertyData != null && propertyData.readMethod() != null && !propertyData.readMethod().annotations().isEmpty()) {
            return visitorContext.annotationMetadata(propertyData.readMethod());
        }
        if (field != null) {
            return field.getAnnotationMetadata();
        }
        return getAnnotationMetadata();
    }

    private AnnotationMetadata writeMemberAnnotationMetadata() {
        if (propertyData != null && propertyData.writeMethod() != null && !propertyData.writeMethod().annotations().isEmpty()) {
            return visitorContext.annotationMetadata(propertyData.writeMethod());
        }
        if (field != null) {
            return field.getAnnotationMetadata();
        }
        return getAnnotationMetadata();
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata.getAnnotationMetadata();
    }

    @Override
    protected MutableAnnotationMetadataDelegate<?> getAnnotationMetadataToWrite() {
        return annotationMetadata;
    }

    @Override
    public PropertyElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        if (propertyData != null) {
            return new ScalaPropertyElement(declaringType, propertyData, visitorContext, annotationMetadata);
        }
        return new ScalaPropertyElement(
            declaringType,
            propertyData,
            type,
            getName(),
            readMethod,
            writeMethod,
            field,
            readAccessKind,
            writeAccessKind,
            excluded,
            getNativeType(),
            getModifiers(),
            annotationMetadata,
            annotationMetadata,
            visitorContext
        );
    }

    @Override
    public AccessKind getReadAccessKind() {
        return readAccessKind;
    }

    @Override
    public AccessKind getWriteAccessKind() {
        return writeAccessKind;
    }

    @Override
    public boolean isExcluded() {
        return excluded;
    }

    @Override
    public boolean isReadOnly() {
        return switch (writeAccessKind) {
            case METHOD -> writeMethod == null;
            case FIELD -> field == null || field.isFinal();
        };
    }

    @Override
    public boolean isWriteOnly() {
        return switch (readAccessKind) {
            case METHOD -> readMethod == null;
            case FIELD -> field == null;
        };
    }

    private static Object selectNativeType(@Nullable MethodElement readMethod, @Nullable MethodElement writeMethod, @Nullable FieldElement field) {
        if (readMethod != null) {
            return readMethod.getNativeType();
        }
        if (writeMethod != null) {
            return writeMethod.getNativeType();
        }
        if (field != null) {
            return field.getNativeType();
        }
        throw new IllegalStateException("A Scala property requires a backing field or method");
    }

    private static Set<io.micronaut.inject.ast.ElementModifier> selectModifiers(
        @Nullable MethodElement readMethod,
        @Nullable MethodElement writeMethod,
        @Nullable FieldElement field) {
        if (readMethod != null) {
            return readMethod.getModifiers();
        }
        if (writeMethod != null) {
            return writeMethod.getModifiers();
        }
        if (field != null) {
            return field.getModifiers();
        }
        return Set.of();
    }
}
