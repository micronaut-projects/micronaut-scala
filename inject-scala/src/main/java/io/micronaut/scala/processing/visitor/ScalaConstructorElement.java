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

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.visitor.ConfigurationReaderVisitor;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.configuration.ConfigurationMetadataBuilder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scala constructor element.
 */
public final class ScalaConstructorElement extends ScalaMethodElement implements ConstructorElement {

    private static final String SCALA_OPTION = "scala.Option";

    ScalaConstructorElement(ScalaClassElement declaringType, ScalaMethodData methodData, ScalaVisitorContext visitorContext) {
        super(declaringType, methodData, visitorContext);
        annotateConfigurationInjectIfNecessary();
    }

    private ScalaConstructorElement(
        ScalaClassElement declaringType,
        ScalaMethodData methodData,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata,
        ParameterElement[] parameters) {
        super(declaringType, methodData, visitorContext, annotationMetadata);
        replaceParameters(parameters);
        annotateConfigurationInjectIfNecessary();
    }

    @Override
    public ConstructorElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaConstructorElement(declaringType, methodData, visitorContext, annotationMetadata, getParameters());
    }

    @Override
    public MethodElement withParameters(ParameterElement... newParameters) {
        return new ScalaConstructorElement(
            declaringType,
            methodData,
            visitorContext,
            getAnnotationMetadata(),
            newParameters
        );
    }

    private void annotateConfigurationInjectIfNecessary() {
        boolean configuration = declaringType.hasStereotype(ConfigurationReader.class)
            && constructorParametersAreBeanProperties();
        if (configuration) {
            annotate(ConfigurationInject.class);
        }
        ParameterElement[] parameters = getParameters();
        ParameterElement[] updatedParameters = new ParameterElement[parameters.length];
        boolean changed = false;
        for (int i = 0; i < parameters.length; i++) {
            ParameterElement parameter = parameters[i];
            if (configuration
                && parameter.stringValue(Property.class, "name").isEmpty()
                && ConfigurationReaderVisitor.isPropertyParameter(parameter, visitorContext)) {
                AnnotationMetadata metadata = visitorContext.getScalaAnnotationMetadataBuilder().annotate(
                    parameter.getAnnotationMetadata(),
                    AnnotationValue.builder(Property.class).member("name", propertyPath(parameter)).build()
                );
                updatedParameters[i] = parameter.withAnnotationMetadata(optionalDefault(parameter, metadata));
                changed = true;
            } else if (readsAProperty(parameter)) {
                // Not a configuration class, but still a parameter bound from a property: a
                // `@Value` on any bean reaches the same resolution, and the same core limitation.
                AnnotationMetadata metadata = optionalDefault(parameter, parameter.getAnnotationMetadata());
                if (metadata != parameter.getAnnotationMetadata()) {
                    updatedParameters[i] = parameter.withAnnotationMetadata(metadata);
                    changed = true;
                } else {
                    updatedParameters[i] = parameter;
                }
            } else {
                updatedParameters[i] = parameter;
            }
        }
        if (changed) {
            replaceParameters(updatedParameters);
        }
    }

    /**
     * Whether this parameter takes its value from a property rather than from another bean.
     */
    private boolean readsAProperty(ParameterElement parameter) {
        return parameter.hasAnnotation(Value.class) || parameter.hasAnnotation(Property.class);
    }

    /**
     * Gives an {@code Option}-typed configuration parameter an empty {@code @Bindable}
     * default, so that an absent property arrives as {@code None}.
     *
     * <p>A property that is set binds fine; one that is *absent* used to fail the whole bean
     * with "Property doesn't exist". Core decides optionality with
     * {@code TypeInformation.isOptional()}, which is {@code type == java.util.Optional}, so a
     * {@code scala.Option} parameter reads as required. The one path that avoids the
     * missing-property error is a {@code @Bindable} default, which core converts to the
     * parameter's type — and the converter maps the empty marker to {@code None}.</p>
     *
     * <p>Only constructor parameters need this. A {@code var} field is initialised by the
     * Scala constructor before any setter runs, so an absent property simply leaves the
     * declared {@code None} in place.</p>
     *
     * <p>An explicit default is never overwritten, so a parameter that already declares one
     * keeps it.</p>
     */
    private AnnotationMetadata optionalDefault(ParameterElement parameter, AnnotationMetadata metadata) {
        if (!SCALA_OPTION.equals(parameter.getType().getName())
            || metadata.stringValue(Bindable.class, "defaultValue").isPresent()) {
            return metadata;
        }
        return visitorContext.getScalaAnnotationMetadataBuilder().annotate(
            metadata,
            AnnotationValue.builder(Bindable.class).member("defaultValue", "").build()
        );
    }

    private String propertyPath(ParameterElement parameter) {
        return ConfigurationMetadataBuilder.calculatePath(
            declaringType,
            declaringType,
            parameter.getGenericType(),
            parameter.getName()
        );
    }

    private boolean constructorParametersAreBeanProperties() {
        ParameterElement[] parameters = getParameters();
        if (parameters.length == 0) {
            return false;
        }
        Set<String> propertyNames = declaringType.getBeanProperties().stream()
            .map(PropertyElement::getName)
            .collect(Collectors.toSet());
        for (ParameterElement parameter : parameters) {
            if (!propertyNames.contains(parameter.getName())) {
                return false;
            }
        }
        return true;
    }
}
