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
import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.ast.FieldElement;

/**
 * Scala enum constant element.
 */
final class ScalaEnumConstantElement extends AbstractScalaMemberElement implements EnumConstantElement {

    private final ScalaEnumElement declaringEnum;
    private final ScalaVisitorContext visitorContext;
    private final ScalaFieldData fieldData;

    ScalaEnumConstantElement(ScalaEnumElement declaringEnum, ScalaFieldData fieldData, ScalaVisitorContext visitorContext) {
        this(declaringEnum, fieldData, visitorContext, visitorContext.annotationMetadata(fieldData));
    }

    private ScalaEnumConstantElement(
        ScalaEnumElement declaringEnum,
        ScalaFieldData fieldData,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata) {
        super(
            declaringEnum,
            fieldData.name(),
            fieldData.nativeType(),
            ENUM_CONSTANT_MODIFIERS,
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.declaringEnum = declaringEnum;
        this.visitorContext = visitorContext;
        this.fieldData = fieldData;
    }

    @Override
    public ClassElement getType() {
        return declaringEnum;
    }

    @Override
    public FieldElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaEnumConstantElement(declaringEnum, fieldData, visitorContext, annotationMetadata);
    }
}
