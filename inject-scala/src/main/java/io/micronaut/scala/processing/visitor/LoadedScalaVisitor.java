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
import io.micronaut.core.order.Ordered;
import io.micronaut.core.reflect.GenericTypeUtils;
import io.micronaut.inject.visitor.TypeElementVisitor;

/**
 * A loaded type element visitor with resolved annotation filters.
 */
final class LoadedScalaVisitor implements Ordered {

    private final TypeElementVisitor<?, ?> visitor;
    private String classAnnotation = Object.class.getName();
    private String elementAnnotation = Object.class.getName();

    LoadedScalaVisitor(TypeElementVisitor<?, ?> visitor) {
        this.visitor = visitor;
        Class<?>[] classes = GenericTypeUtils.resolveInterfaceTypeArguments(
            visitor.getClass(),
            TypeElementVisitor.class
        );
        if (classes != null && classes.length == 2) {
            classAnnotation = classes[0] == Object.class ? visitor.getClassType() : classes[0].getName();
            elementAnnotation = classes[1] == Object.class ? visitor.getElementType() : classes[1].getName();
        } else {
            classAnnotation = visitor.getClassType();
            elementAnnotation = visitor.getElementType();
        }
        if (classAnnotation == null || classAnnotation.equals(Object.class.getName())) {
            classAnnotation = Object.class.getName();
        }
        if (elementAnnotation == null || elementAnnotation.equals(Object.class.getName())) {
            elementAnnotation = Object.class.getName();
        }
    }

    TypeElementVisitor<?, ?> getVisitor() {
        return visitor;
    }

    boolean matchesClass(AnnotationMetadata annotationMetadata) {
        return classAnnotation.equals(Object.class.getName()) || annotationMetadata.hasStereotype(classAnnotation);
    }

    boolean matchesElement(AnnotationMetadata annotationMetadata) {
        return elementAnnotation.equals(Object.class.getName()) || annotationMetadata.hasStereotype(elementAnnotation);
    }

    @Override
    public int getOrder() {
        return visitor.getOrder();
    }
}
