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
import io.micronaut.core.util.ArgumentUtils;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadataFactory;
import io.micronaut.inject.ast.beans.BeanParameterElement;
import io.micronaut.inject.processing.definition.ElementBeanDefinitionBuilderFactory;
import io.micronaut.inject.utils.BeanInjectionUtils;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.writer.AbstractBeanDefinitionBuilder;
import io.micronaut.context.beans.definition.MethodDefinition;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Scala implementation of {@link AbstractBeanDefinitionBuilder}.
 */
class ScalaBeanDefinitionBuilder extends AbstractBeanDefinitionBuilder {

    private final ScalaAnnotationMetadataBuilder annotationMetadataBuilder;

    ScalaBeanDefinitionBuilder(
        Element originatingElement,
        ClassElement beanType,
        ElementAnnotationMetadataFactory elementAnnotationMetadataFactory,
        ScalaVisitorContext visitorContext) {
        super(originatingElement, beanType, visitorContext, elementAnnotationMetadataFactory);
        if (visitorContext.getVisitorKind() == TypeElementVisitor.VisitorKind.ISOLATING) {
            if (getClass() == ScalaBeanDefinitionBuilder.class) {
                visitorContext.addBeanDefinitionBuilder(this);
            }
        } else {
            visitorContext.fail("Cannot add bean definition using addAssociatedBean(..) from a AGGREGATING TypeElementVisitor, consider overriding getVisitorKind()", originatingElement);
        }
        this.annotationMetadataBuilder = visitorContext.getScalaAnnotationMetadataBuilder();
    }

    @Override
    protected AbstractBeanDefinitionBuilder createChildBean(FieldElement producerField) {
        ClassElement parentType = getBeanType();
        return new ScalaBeanDefinitionBuilder(
            getOriginatingElement(),
            producerField.getGenericField().getType(),
            elementAnnotationMetadataFactory,
            (ScalaVisitorContext) visitorContext
        ) {
            @Override
            public Element getProducingElement() {
                return producerField;
            }

            @Override
            public ClassElement getDeclaringElement() {
                return producerField.getDeclaringType();
            }

            @Override
            public <R> List<R> build(ElementBeanDefinitionBuilderFactory<R> beanDefinitionBuilderFactory) {
                ClassElement newParent = parentType.withAnnotationMetadata(parentType.copyAnnotationMetadata());
                return beanDefinitionBuilderFactory.factoryField(producerField.withAnnotationMetadata(
                    new AnnotationMetadataHierarchy(newParent.getDeclaredMetadata(), producerField.getDeclaredMetadata(), getAnnotationMetadata())
                )).build();
            }
        };
    }

    @Override
    protected AbstractBeanDefinitionBuilder createChildBean(MethodElement producerMethod) {
        ClassElement parentType = getBeanType();
        return new ScalaBeanDefinitionBuilder(
            getOriginatingElement(),
            producerMethod.getGenericReturnType().getType(),
            elementAnnotationMetadataFactory,
            (ScalaVisitorContext) visitorContext
        ) {
            private BeanParameterElement @Nullable [] parameters;

            @Override
            public Element getProducingElement() {
                return producerMethod;
            }

            @Override
            public ClassElement getDeclaringElement() {
                return producerMethod.getDeclaringType();
            }

            @Override
            protected BeanParameterElement[] getParameters() {
                if (parameters == null) {
                    parameters = initBeanParameters(producerMethod.getParameters());
                }
                return parameters;
            }

            @Override
            public <R> List<R> build(ElementBeanDefinitionBuilderFactory<R> beanDefinitionBuilderFactory) {
                ClassElement newParent = parentType.withAnnotationMetadata(parentType.copyAnnotationMetadata());
                MethodElement methodElement = producerMethod.withAnnotationMetadata(
                    new AnnotationMetadataHierarchy(newParent.getDeclaredMetadata(), producerMethod.getDeclaredMetadata(), getAnnotationMetadata())
                );
                return beanDefinitionBuilderFactory.factoryMethod(new MethodDefinition<>(
                    methodElement,
                    methodElement.getAnnotationMetadata(),
                    Arrays.stream(getParameters())
                        .map(parameter -> BeanInjectionUtils.getInjectionPoint(newParent, parameter.getGenericType(), parameter, parameter.getName(), visitorContext))
                        .toList(),
                    methodElement.isReflectionRequired()
                )).build();
            }
        };
    }

    @Override
    protected <T extends Annotation> void annotate(AnnotationMetadata annotationMetadata, String annotationType, Consumer<AnnotationValueBuilder<T>> consumer) {
        ArgumentUtils.requireNonNull("annotationMetadata", annotationMetadata);
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        ArgumentUtils.requireNonNull("consumer", consumer);
        AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType);
        consumer.accept(builder);
        annotationMetadataBuilder.annotate(annotationMetadata, builder.build());
    }

    @Override
    protected <T extends Annotation> void annotate(AnnotationMetadata annotationMetadata, AnnotationValue<T> annotationValue) {
        ArgumentUtils.requireNonNull("annotationMetadata", annotationMetadata);
        ArgumentUtils.requireNonNull("annotationValue", annotationValue);
        annotationMetadataBuilder.annotate(annotationMetadata, annotationValue);
    }

    @Override
    protected void removeStereotype(AnnotationMetadata annotationMetadata, String annotationType) {
        ArgumentUtils.requireNonNull("annotationMetadata", annotationMetadata);
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        annotationMetadataBuilder.removeStereotype(annotationMetadata, annotationType);
    }

    @Override
    protected <T extends Annotation> void removeAnnotationIf(AnnotationMetadata annotationMetadata, Predicate<AnnotationValue<T>> predicate) {
        ArgumentUtils.requireNonNull("annotationMetadata", annotationMetadata);
        ArgumentUtils.requireNonNull("predicate", predicate);
        annotationMetadataBuilder.removeAnnotationIf(annotationMetadata, predicate);
    }

    @Override
    protected void removeAnnotation(AnnotationMetadata annotationMetadata, String annotationType) {
        ArgumentUtils.requireNonNull("annotationMetadata", annotationMetadata);
        ArgumentUtils.requireNonNull("annotationType", annotationType);
        annotationMetadataBuilder.removeAnnotation(annotationMetadata, annotationType);
    }

}
