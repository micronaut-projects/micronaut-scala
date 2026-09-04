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

    private static final String SCALA_ITERABLE = "scala.collection.Iterable";
    private static final String SCALA_MAP = "scala.collection.Map";
    private static final String SCALA_OPTION = "scala.Option";

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
            ClassElement beanValueType = requestedType.getTypeArguments().getOrDefault("V", objectType);
            return Optional.of(new BeanDefinitionInjectionPoint.MapOfBeansInjectionPoint<>(
                requestedType,
                annotationMetadata,
                beanValueType
            ));
        }
        if (SCALA_OPTION.equals(requestedType.getName())) {
            ClassElement objectType = visitorContext.getClassElement(Object.class).orElseThrow();
            return Optional.of(new BeanDefinitionInjectionPoint.OptionalBeanInjectionPoint<>(
                requestedType,
                annotationMetadata,
                requestedType.getFirstTypeArgument().orElse(objectType)
            ));
        }
        return Optional.empty();
    }

    /**
     * A collection of beans, i.e. anything Micronaut can populate from a {@code java.util.Collection}
     * via {@link io.micronaut.scala.processing.ScalaCollectionConverterRegistrar}. Matching on the
     * {@code scala.collection.} package prefix instead would also catch {@code Iterator},
     * {@code View}, {@code IterableOnce} and friends, none of which are injectable.
     */
    private static boolean isScalaCollection(ClassElement type) {
        return type.isAssignable(SCALA_ITERABLE) && !isScalaMap(type);
    }

    /**
     * Maps are handled separately: they inject bean names to beans rather than a plain collection,
     * and {@code scala.collection.Map} is itself a {@code scala.collection.Iterable} of pairs.
     */
    private static boolean isScalaMap(ClassElement type) {
        return type.isAssignable(SCALA_MAP);
    }

    private static boolean isInjectableScalaMap(ClassElement type) {
        if (!isScalaMap(type)) {
            return false;
        }
        Map<String, ClassElement> typeArguments = type.getTypeArguments();
        ClassElement keyType = typeArguments.get("K");
        return keyType != null && keyType.isAssignable(CharSequence.class);
    }
}
