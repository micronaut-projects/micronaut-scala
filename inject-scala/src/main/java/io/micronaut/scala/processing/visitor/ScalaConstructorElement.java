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
import io.micronaut.context.visitor.ConfigurationReaderVisitor;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
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
        if (declaringType.hasStereotype(ConfigurationReader.class) && constructorParametersAreBeanProperties()) {
            annotate(ConfigurationInject.class);
            ParameterElement[] parameters = getParameters();
            ParameterElement[] updatedParameters = new ParameterElement[parameters.length];
            boolean changed = false;
            for (int i = 0; i < parameters.length; i++) {
                ParameterElement parameter = parameters[i];
                if (parameter.stringValue(Property.class, "name").isEmpty()
                    && ConfigurationReaderVisitor.isPropertyParameter(parameter, visitorContext)) {
                    updatedParameters[i] = parameter.withAnnotationMetadata(
                        visitorContext.getScalaAnnotationMetadataBuilder().annotate(
                            parameter.getAnnotationMetadata(),
                            AnnotationValue.builder(Property.class).member("name", propertyPath(parameter)).build()
                        )
                    );
                    changed = true;
                } else {
                    updatedParameters[i] = parameter;
                }
            }
            if (changed) {
                replaceParameters(updatedParameters);
            }
        }
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
