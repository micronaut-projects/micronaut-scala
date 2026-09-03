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

import io.micronaut.context.BeanRegistration;
import io.micronaut.context.beans.definition.BeanDefinitionInjectionPoint;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.BeanDefinitionInjectionPointResolver;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.Map;
import java.util.Optional;

/**
 * Maps Scala collection and option types to Micronaut's language-neutral
 * injection point model.
 */
final class ScalaBeanDefinitionInjectionPointResolver implements BeanDefinitionInjectionPointResolver {

    @Override
    public Optional<BeanDefinitionInjectionPoint<ClassElement>> resolve(
        ClassElement beanType,
        ClassElement requestedType,
        AnnotationMetadata annotationMetadata,
        String parameterName,
        VisitorContext visitorContext) {
        if (isScalaCollection(requestedType)) {
            ClassElement typeArgument = requestedType.getFirstTypeArgument().orElse(null);
            if (typeArgument != null && !typeArgument.isPrimitive()) {
                if (typeArgument.isAssignable(BeanRegistration.class)) {
                    return Optional.of(new BeanDefinitionInjectionPoint.BeanRegistrationsInjectionPoint<>(
                        requestedType,
                        annotationMetadata,
                        typeArgument.getFirstTypeArgument().orElseThrow()
                    ));
                }
                return Optional.of(new BeanDefinitionInjectionPoint.BeansInjectionPoint<>(
                    requestedType,
                    annotationMetadata,
                    typeArgument
                ));
            }
            return Optional.of(new BeanDefinitionInjectionPoint.BeanInjectionPoint<>(requestedType, annotationMetadata));
        }
        if (isInjectableScalaMap(requestedType)) {
            ClassElement objectType = visitorContext.getClassElement(Object.class).orElseThrow();
            ClassElement beanValueType = requestedType.getTypeArguments().values().stream()
                .skip(1)
                .findFirst()
                .orElse(objectType);
            return Optional.of(new BeanDefinitionInjectionPoint.MapOfBeansInjectionPoint<>(
                requestedType,
                annotationMetadata,
                beanValueType
            ));
        }
        if ("scala.Option".equals(requestedType.getName())) {
            ClassElement objectType = visitorContext.getClassElement(Object.class).orElseThrow();
            return Optional.of(new BeanDefinitionInjectionPoint.OptionalBeanInjectionPoint<>(
                requestedType,
                annotationMetadata,
                requestedType.getFirstTypeArgument().orElse(objectType)
            ));
        }
        return Optional.empty();
    }

    private static boolean isScalaCollection(ClassElement type) {
        return type.getName().startsWith("scala.collection.") && !type.getName().contains(".Map");
    }

    private static boolean isInjectableScalaMap(ClassElement type) {
        if (!type.getName().startsWith("scala.collection.") || !type.getName().contains(".Map")) {
            return false;
        }
        Map<String, ClassElement> typeArguments = type.getTypeArguments();
        ClassElement keyType = typeArguments.values().stream().findFirst().orElse(null);
        return keyType != null && keyType.isAssignable(CharSequence.class);
    }
}
