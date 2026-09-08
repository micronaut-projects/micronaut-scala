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
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MethodElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MutatedMethodElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Scala method element.
 */
public class ScalaMethodElement extends AbstractScalaMemberElement implements MethodElement {

    protected final ScalaClassElement declaringType;
    protected final ScalaVisitorContext visitorContext;
    protected final ScalaMethodData methodData;
    private final ClassElement owningType;
    @Nullable
    private final AnnotationMetadata presetAnnotationMetadata;
    @Nullable
    private ElementAnnotationMetadata methodAnnotationMetadata;
    @Nullable
    private AnnotationMetadata annotationMetadata;
    @Nullable
    private ClassElement returnType;
    private ParameterElement[] parameters;

    ScalaMethodElement(ScalaClassElement declaringType, ScalaMethodData methodData, ScalaVisitorContext visitorContext) {
        this(declaringType, methodData, visitorContext, null);
    }

    ScalaMethodElement(
        ScalaClassElement declaringType,
        ScalaMethodData methodData,
        ScalaVisitorContext visitorContext,
        @Nullable
        AnnotationMetadata presetAnnotationMetadata) {
        this(declaringType, declaringType, methodData, visitorContext, presetAnnotationMetadata);
    }

    private ScalaMethodElement(
        ScalaClassElement declaringType,
        ClassElement owningType,
        ScalaMethodData methodData,
        ScalaVisitorContext visitorContext,
        @Nullable
        AnnotationMetadata presetAnnotationMetadata) {
        super(
            declaringType,
            methodData.name(),
            methodData.nativeType(),
            methodData.modifiers(),
            visitorContext.annotationMetadata(methodData),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.declaringType = declaringType;
        this.owningType = owningType;
        this.visitorContext = visitorContext;
        this.methodData = methodData;
        this.presetAnnotationMetadata = presetAnnotationMetadata;
        this.parameters = methodData.parameters().stream()
            .map(parameter -> new ScalaParameterElement(this, parameter, visitorContext))
            .toArray(ParameterElement[]::new);
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        if (annotationMetadata == null) {
            if (this instanceof ConstructorElement) {
                annotationMetadata = getMethodAnnotationMetadata();
            } else if (presetAnnotationMetadata instanceof AnnotationMetadataHierarchy annotationMetadataHierarchy) {
                annotationMetadata = new AnnotationMetadataHierarchy(
                    annotationMetadataHierarchy.getRootMetadata(),
                    getMethodAnnotationMetadata()
                );
            } else if (presetAnnotationMetadata != null) {
                annotationMetadata = new MutatedMethodElementAnnotationMetadata(this, getMethodAnnotationMetadata());
            } else {
                annotationMetadata = new MethodElementAnnotationMetadata(this);
            }
        }
        return annotationMetadata;
    }

    @Override
    protected MutableAnnotationMetadataDelegate<?> getAnnotationMetadataToWrite() {
        return getMethodAnnotationMetadata();
    }

    @Override
    public MutableAnnotationMetadataDelegate<AnnotationMetadata> getMethodAnnotationMetadata() {
        if (methodAnnotationMetadata == null) {
            if (presetAnnotationMetadata instanceof AnnotationMetadataHierarchy annotationMetadataHierarchy) {
                methodAnnotationMetadata = mutableAnnotationMetadata(annotationMetadataHierarchy.getDeclaredMetadata());
            } else if (presetAnnotationMetadata != null) {
                methodAnnotationMetadata = mutableAnnotationMetadata(presetAnnotationMetadata);
            } else {
                methodAnnotationMetadata = getElementAnnotationMetadata();
            }
        }
        return methodAnnotationMetadata;
    }

    private ElementAnnotationMetadata mutableAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return visitorContext.getElementAnnotationMetadataFactory().buildMutable(annotationMetadata);
    }

    @Override
    public ClassElement getReturnType() {
        if (returnType == null) {
            returnType = visitorContext.getElementFactory().newClassElement(methodData.returnType());
        }
        return returnType;
    }

    @Override
    public List<? extends GenericPlaceholderElement> getDeclaredTypeVariables() {
        return methodData.typeParameters().stream()
            .map(visitorContext.getElementFactory()::newClassElement)
            .map(GenericPlaceholderElement.class::cast)
            .toList();
    }

    @Override
    public Map<String, ClassElement> getDeclaredTypeArguments() {
        return getDeclaredTypeVariables().stream()
            .collect(Collectors.toMap(
                GenericPlaceholderElement::getVariableName,
                Function.identity(),
                (left, right) -> left,
                java.util.LinkedHashMap::new
            ));
    }

    @Override
    public ClassElement[] getThrownTypes() {
        return methodData.thrownTypes().stream()
            .map(visitorContext.getElementFactory()::newClassElement)
            .toArray(ClassElement[]::new);
    }

    @Override
    public ParameterElement[] getParameters() {
        return parameters;
    }

    protected final void replaceParameters(ParameterElement[] parameters) {
        this.parameters = parameters;
    }

    @Override
    public MethodElement withParameters(ParameterElement... newParameters) {
        ScalaMethodElement methodElement = new ScalaMethodElement(declaringType, owningType, methodData, visitorContext, getAnnotationMetadata());
        methodElement.replaceParameters(newParameters);
        return methodElement;
    }

    @Override
    public MethodElement withNewOwningType(ClassElement owningType) {
        ScalaMethodElement methodElement = new ScalaMethodElement(declaringType, owningType, methodData, visitorContext, getAnnotationMetadata());
        methodElement.replaceParameters(parameters);
        return methodElement;
    }

    @Override
    public MethodElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaMethodElement(declaringType, owningType, methodData, visitorContext, annotationMetadata);
    }

    @Override
    public BeanElementBuilder addAssociatedBean(ClassElement type) {
        return new ScalaBeanDefinitionBuilder(
            this,
            type,
            visitorContext.getElementAnnotationMetadataFactory(),
            visitorContext
        );
    }

    @Override
    public boolean isDefault() {
        return !methodData.constructor() && declaringType.isInterface() && !isAbstract();
    }

    /**
     * The class this method was reached through.
     *
     * <p>This used to answer {@code declaringType} for a default method, which is every
     * concrete trait method, so {@code withNewOwningType} was a no-op for exactly the case it
     * exists for. Class-level AOP advice reaches a method through the owning type's metadata,
     * so a method inherited from a trait and not overridden was never advised: the bean was
     * proxied, the proxy declared the method, and the interceptor chain for it was empty --
     * {@code @Transactional} or {@code @Cacheable} on such a method did nothing, silently.</p>
     */
    @Override
    public ClassElement getOwningType() {
        return owningType;
    }

    @Override
    public boolean overrides(MethodElement overridden) {
        // `BeanDefinitionCreatorFactory` uses this to merge annotation metadata from the
        // overridden parent method, to avoid emitting a second injection point, and to make
        // interception decisions. Answering false unconditionally made an `@Inject`,
        // `@PostConstruct` or `@Executable` method overridden in a subclass look like two
        // unrelated methods.
        if (overridden == this || isStatic() || overridden.isStatic()) {
            return false;
        }
        // A private method is never virtual, so it can only ever hide.
        if (isPrivate() || overridden.isPrivate()) {
            return false;
        }
        return overridesSignatureOf(overridden) && declaringTypeIsBelow(overridden);
    }

    @Override
    public boolean hides(MethodElement hiddenMethod) {
        // Hiding is the static counterpart of overriding: same signature, but resolved on
        // the declared type rather than the runtime one.
        if (hiddenMethod == this || !isStatic() || !hiddenMethod.isStatic()) {
            return false;
        }
        if (isPrivate() || hiddenMethod.isPrivate()) {
            return false;
        }
        return overridesSignatureOf(hiddenMethod) && declaringTypeIsBelow(hiddenMethod);
    }

    /**
     * Name plus erased parameter signature. Erasure is the right comparison because that is
     * what decides overriding on the JVM, and it is what the generated bytecode will use.
     */
    private boolean overridesSignatureOf(MethodElement other) {
        if (!getName().equals(other.getName())) {
            return false;
        }
        ParameterElement[] parameters = getParameters();
        ParameterElement[] otherParameters = other.getParameters();
        if (parameters.length != otherParameters.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            ClassElement type = parameters[i].getType();
            ClassElement otherType = otherParameters[i].getType();
            if (type.getArrayDimensions() != otherType.getArrayDimensions()
                || !type.getName().equals(otherType.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Overriding is directional: this method must be declared on a subtype of the other's
     * declaring type, and not on the same type, or two unrelated classes that happen to
     * share a signature would be reported as overriding each other.
     */
    private boolean declaringTypeIsBelow(MethodElement other) {
        ClassElement otherDeclaringType = other.getDeclaringType();
        if (declaringType.getName().equals(otherDeclaringType.getName())) {
            return false;
        }
        return declaringType.isAssignable(otherDeclaringType);
    }

    @Override
    protected Object equalityKey() {
        return new MethodElementKey(
            declaringType,
            methodData.name(),
            methodData.constructor(),
            methodData.parameters().stream()
                .map(parameter -> typeKey(parameter.type()))
                .toList()
        );
    }

    private static TypeKey typeKey(ScalaTypeData typeData) {
        return new TypeKey(typeData.name(), typeData.arrayDimensions());
    }

    private record MethodElementKey(
        ClassElement declaringType,
        String name,
        boolean constructor,
        List<TypeKey> parameterTypes
    ) {
    }

    private record TypeKey(String name, int arrayDimensions) {
    }
}
