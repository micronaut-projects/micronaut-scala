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
import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.ast.EnumElement;
import io.micronaut.inject.ast.ElementModifier;
import io.micronaut.inject.ast.MethodElement;

import java.util.List;
import java.util.Optional;

/**
 * Scala enum element.
 */
final class ScalaEnumElement extends ScalaClassElement implements EnumElement {

    private final ScalaClassData classData;
    private final ScalaVisitorContext visitorContext;

    ScalaEnumElement(ScalaClassData classData, ScalaVisitorContext visitorContext) {
        super(classData, visitorContext);
        this.classData = classData;
        this.visitorContext = visitorContext;
    }

    private ScalaEnumElement(ScalaClassData classData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(classData, visitorContext, annotationMetadata);
        this.classData = classData;
        this.visitorContext = visitorContext;
    }

    @Override
    public List<String> values() {
        return classData.fields().stream()
            .filter(ScalaFieldData::enumConstant)
            .map(ScalaFieldData::name)
            .toList();
    }

    @Override
    public List<EnumConstantElement> elements() {
        return classData.fields().stream()
            .filter(ScalaFieldData::enumConstant)
            .map(field -> new ScalaEnumConstantElement(this, field, visitorContext))
            .map(EnumConstantElement.class::cast)
            .toList();
    }

    @Override
    public Optional<MethodElement> getEnumValueOfMethod() {
        return classData.methods().stream()
            .filter(method -> "valueOf".equals(method.name()))
            .filter(method -> method.modifiers().contains(ElementModifier.STATIC))
            .filter(method -> method.parameters().size() == 1)
            .filter(method -> String.class.getName().equals(method.parameters().getFirst().type().name()))
            .findFirst()
            .map(this::methodElement);
    }

    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaEnumElement(classData, visitorContext, annotationMetadata);
    }
}
