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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementFactory;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadataFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for Scala Element API wrappers.
 */
public final class ScalaElementFactory implements ElementFactory<Object, ScalaClassData, ScalaMethodData, ScalaFieldData> {

    private final ScalaVisitorContext visitorContext;

    ScalaElementFactory(ScalaVisitorContext visitorContext) {
        this.visitorContext = visitorContext;
    }

    @Override
    public ClassElement newClassElement(ScalaClassData type, ElementAnnotationMetadataFactory annotationMetadataFactory) {
        return newClassElement(type);
    }

    @Override
    public ClassElement newSourceClassElement(ScalaClassData type, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        return newClassElement(type);
    }

    ClassElement newClassElement(ScalaClassData type) {
        return visitorContext.sourceClassElement(type.name())
            .orElseGet(() -> newClassElementForData(type));
    }

    ScalaClassElement newClassElementForData(ScalaClassData type) {
        if (type.enumType()) {
            return new ScalaEnumElement(type, visitorContext);
        }
        return new ScalaClassElement(type, visitorContext);
    }

    ClassElement newClassElement(ScalaTypeData type) {
        if (type.wildcard()) {
            return new ScalaWildcardElement(type, visitorContext);
        }
        if (type.genericPlaceholder()) {
            return new ScalaGenericPlaceholderElement(type, visitorContext);
        }
        if (type.primitive()) {
            return switch (type.name()) {
                case "boolean" -> primitiveElement(PrimitiveElement.BOOLEAN, type.arrayDimensions());
                case "byte" -> primitiveElement(PrimitiveElement.BYTE, type.arrayDimensions());
                case "char" -> primitiveElement(PrimitiveElement.CHAR, type.arrayDimensions());
                case "double" -> primitiveElement(PrimitiveElement.DOUBLE, type.arrayDimensions());
                case "float" -> primitiveElement(PrimitiveElement.FLOAT, type.arrayDimensions());
                case "int" -> primitiveElement(PrimitiveElement.INT, type.arrayDimensions());
                case "long" -> primitiveElement(PrimitiveElement.LONG, type.arrayDimensions());
                case "short" -> primitiveElement(PrimitiveElement.SHORT, type.arrayDimensions());
                case "void" -> primitiveElement(PrimitiveElement.VOID, type.arrayDimensions());
                default -> new ScalaClassElement(type, visitorContext, visitorContext.annotationMetadata(type));
            };
        }
        if (!type.annotatedTypeUse() && type.typeArguments().isEmpty()) {
            return visitorContext.sourceClassElement(type.name())
                .map(classElement -> type.arrayDimensions() == 0 ? classElement : classElement.withArrayDimensions(type.arrayDimensions()))
                .orElseGet(() -> new ScalaClassElement(type, visitorContext, visitorContext.annotationMetadata(type)));
        }
        return new ScalaClassElement(type, visitorContext, visitorContext.annotationMetadata(type));
    }

    private ClassElement primitiveElement(PrimitiveElement element, int arrayDimensions) {
        return arrayDimensions == 0 ? element : element.withArrayDimensions(arrayDimensions);
    }

    Map<String, ClassElement> typeArguments(ScalaTypeData type) {
        if (type.typeArguments().isEmpty()) {
            return Map.of();
        }
        Map<String, ClassElement> converted = new LinkedHashMap<>();
        type.typeArguments().forEach((name, typeData) -> converted.put(name, newClassElement(typeData)));
        return converted;
    }

    @Override
    public MethodElement newSourceMethodElement(ClassElement owningClass, ScalaMethodData method, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        return newMethodElement(owningClass, method, elementAnnotationMetadataFactory);
    }

    @Override
    public MethodElement newMethodElement(ClassElement owningClass, ScalaMethodData method, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        return ((ScalaClassElement) owningClass).methodElement(method);
    }

    @Override
    public ConstructorElement newConstructorElement(ClassElement owningClass, ScalaMethodData constructor, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        return ((ScalaClassElement) owningClass).constructorElement(constructor);
    }

    @Override
    public io.micronaut.inject.ast.EnumConstantElement newEnumConstantElement(ClassElement owningClass, ScalaFieldData enumConstant, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        if (owningClass instanceof ScalaEnumElement scalaEnumElement) {
            return scalaEnumElement.enumConstantElement(enumConstant);
        }
        throw new IllegalArgumentException("Declaring class must be a ScalaEnumElement");
    }

    @Override
    public FieldElement newFieldElement(ClassElement owningClass, ScalaFieldData field, ElementAnnotationMetadataFactory elementAnnotationMetadataFactory) {
        if (field.enumConstant()) {
            return newEnumConstantElement(owningClass, field, elementAnnotationMetadataFactory);
        }
        return ((ScalaClassElement) owningClass).fieldElement(field);
    }
}
