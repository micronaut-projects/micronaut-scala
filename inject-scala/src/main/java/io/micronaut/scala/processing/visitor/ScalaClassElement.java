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

import io.micronaut.context.annotation.BeanProperties;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.type.DefaultArgument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ArrayableClassElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PackageElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.ast.PropertyElementQuery;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import io.micronaut.inject.ast.beans.BeanElementBuilder;
import io.micronaut.inject.ast.utils.AstBeanPropertiesUtils;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Scala class element backed by compiler plugin model data.
 */
public class ScalaClassElement extends AbstractScalaElement implements ArrayableClassElement {

    private final ScalaVisitorContext visitorContext;
    private final ScalaTypeData typeData;
    private final @Nullable ScalaClassData classData;
    private final ScalaElementFactory elementFactory;
    private final IdentityHashMap<ScalaMethodData, ScalaConstructorElement> constructorElements = new IdentityHashMap<>();
    private final IdentityHashMap<ScalaMethodData, ScalaMethodElement> methodElements = new IdentityHashMap<>();
    private final IdentityHashMap<ScalaFieldData, ScalaFieldElement> fieldElements = new IdentityHashMap<>();
    private final IdentityHashMap<ScalaFieldData, ScalaEnumConstantElement> enumConstantElements = new IdentityHashMap<>();
    private final IdentityHashMap<ScalaPropertyData, ScalaPropertyElement> propertyElements = new IdentityHashMap<>();
    private @Nullable Map<String, ClassElement> typeArgumentElements;

    ScalaClassElement(ScalaClassData classData, ScalaVisitorContext visitorContext) {
        this(classData, visitorContext, visitorContext.annotationMetadata(classData));
    }

    ScalaClassElement(ScalaClassData classData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(
            classData.name(),
            classData.nativeType(),
            classData.modifiers(),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.visitorContext = visitorContext;
        this.classData = classData;
        this.typeData = new ScalaTypeData(classData.name(), false, 0, classData.interfaceType(), Map.of());
        this.elementFactory = visitorContext.getElementFactory();
    }

    ScalaClassElement(ScalaTypeData typeData, ScalaVisitorContext visitorContext, AnnotationMetadata annotationMetadata) {
        super(
            typeData.name(),
            typeData.name(),
            Set.of(ElementModifier.PUBLIC),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.visitorContext = visitorContext;
        this.typeData = typeData;
        this.classData = null;
        this.elementFactory = visitorContext.getElementFactory();
    }

    @Override
    public boolean isInterface() {
        return classData == null ? typeData.interfaceType() : classData.interfaceType();
    }

    @Override
    public boolean isEnum() {
        return classData != null && classData.enumType();
    }

    @Override
    @SuppressWarnings("unchecked")
    public MutableAnnotationMetadataDelegate<AnnotationMetadata> getTypeAnnotationMetadata() {
        if (typeData.annotatedTypeUse()) {
            return getElementAnnotationMetadata();
        }
        return (MutableAnnotationMetadataDelegate<AnnotationMetadata>) MutableAnnotationMetadataDelegate.EMPTY;
    }

    @Override
    public boolean isAssignable(String type) {
        if (getName().equals(type) || Object.class.getName().equals(type)) {
            return true;
        }
        if (classData != null) {
            return isAssignableTo(type, classData, Set.of(getName()));
        }
        return isTypeAssignable(type, typeData, Set.of());
    }

    private boolean isAssignableTo(String type, ScalaClassData data, Set<String> visited) {
        if (data.superType() != null && isTypeAssignable(type, data.superType(), visited)) {
            return true;
        }
        for (ScalaTypeData interfaceType : data.interfaces()) {
            if (isTypeAssignable(type, interfaceType, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTypeAssignable(String type, ScalaTypeData candidate, Set<String> visited) {
        if (type.equals(candidate.name()) || Object.class.getName().equals(type)) {
            return true;
        }
        if (!visited.contains(candidate.name())) {
            Set<String> nextVisited = new java.util.HashSet<>(visited);
            nextVisited.add(candidate.name());
            Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(candidate.name());
            if (sourceElement.isPresent() && sourceElement.get().classData != null) {
                return isAssignableTo(type, sourceElement.get().classData, nextVisited);
            }
            if (sourceElement.isPresent()) {
                return false;
            }
            if (candidate.superType() != null && isTypeAssignable(type, candidate.superType(), nextVisited)) {
                return true;
            }
            for (ScalaTypeData interfaceType : candidate.interfaces()) {
                if (isTypeAssignable(type, interfaceType, nextVisited)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Optional<ClassElement> getSuperType() {
        ScalaTypeData superType = classData == null ? typeData.superType() : classData.superType();
        if (superType == null) {
            return Optional.empty();
        }
        return Optional.of(elementFactory.newClassElement(superType));
    }

    @Override
    public Collection<ClassElement> getInterfaces() {
        Collection<ScalaTypeData> interfaces = classData == null ? typeData.interfaces() : classData.interfaces();
        return interfaces.stream()
            .map(elementFactory::newClassElement)
            .toList();
    }

    @Override
    public boolean isInner() {
        return classData != null && classData.enclosingTypeName() != null;
    }

    @Override
    public Optional<ClassElement> getEnclosingType() {
        if (classData == null || classData.enclosingTypeName() == null) {
            return Optional.empty();
        }
        return visitorContext.sourceClassElement(classData.enclosingTypeName())
            .map(ClassElement.class::cast);
    }

    @Override
    public BeanElementBuilder addAssociatedBean(ClassElement type) {
        if (classData == null) {
            throw new UnsupportedOperationException("Element of type [" + getClass() + "] does not support adding associated beans at compilation time");
        }
        return new ScalaBeanDefinitionBuilder(
            this,
            type,
            visitorContext.getElementAnnotationMetadataFactory(),
            visitorContext
        );
    }

    @Override
    public Map<String, ClassElement> getTypeArguments() {
        if (typeArgumentElements != null) {
            return typeArgumentElements;
        }
        if (classData != null && typeData.typeArguments().isEmpty() && !classData.typeParameters().isEmpty()) {
            Map<String, ClassElement> declaredTypeArguments = new LinkedHashMap<>();
            for (ScalaTypeData typeParameter : classData.typeParameters()) {
                ClassElement typeParameterElement = elementFactory.newClassElement(typeParameter);
                if (typeParameterElement instanceof GenericPlaceholderElement placeholderElement) {
                    declaredTypeArguments.put(placeholderElement.getVariableName(), typeParameterElement);
                }
            }
            typeArgumentElements = declaredTypeArguments;
        } else {
            typeArgumentElements = elementFactory.typeArguments(typeData);
        }
        return typeArgumentElements;
    }

    @Override
    public List<? extends GenericPlaceholderElement> getDeclaredGenericPlaceholders() {
        if (classData == null) {
            return List.of();
        }
        return classData.typeParameters().stream()
            .map(elementFactory::newClassElement)
            .map(GenericPlaceholderElement.class::cast)
            .toList();
    }

    @Override
    public List<PropertyElement> getBeanProperties() {
        return getBeanProperties(PropertyElementQuery.of(getAnnotationMetadata()));
    }

    @Override
    public List<PropertyElement> getSyntheticBeanProperties() {
        if (classData == null) {
            return List.of();
        }
        return classData.properties().stream()
            .map(this::propertyElement)
            .map(PropertyElement.class::cast)
            .toList();
    }

    @Override
    public List<PropertyElement> getBeanProperties(PropertyElementQuery propertyElementQuery) {
        if (classData == null) {
            return List.of();
        }
        Set<BeanProperties.AccessKind> accessKinds = propertyElementQuery.getAccessKinds();
        if (accessKinds.contains(BeanProperties.AccessKind.FIELD) && !accessKinds.contains(BeanProperties.AccessKind.METHOD)) {
            return AstBeanPropertiesUtils.resolveBeanProperties(
                propertyElementQuery,
                this,
                () -> List.of(),
                () -> getEnclosedElements(ElementQuery.ALL_FIELDS),
                false,
                Collections.emptySet(),
                methodElement -> Optional.empty(),
                methodElement -> Optional.empty(),
                this::mapBeanPropertyElement
            );
        }
        Map<String, PropertyElement> properties = new LinkedHashMap<>();
        classData.properties().stream()
            .map(this::propertyElement)
            .filter(propertyElement -> matches(propertyElementQuery, propertyElement))
            .forEach(propertyElement -> properties.put(propertyElement.getName(), propertyElement));
        AstBeanPropertiesUtils.resolveBeanProperties(
            propertyElementQuery,
            this,
            () -> getEnclosedElements(ElementQuery.ALL_METHODS),
            () -> beanPropertyFields(accessKinds),
            false,
            Collections.emptySet(),
            methodElement -> Optional.empty(),
            methodElement -> Optional.empty(),
            this::mapBeanPropertyElement
        ).forEach(propertyElement -> properties.putIfAbsent(propertyElement.getName(), propertyElement));
        return List.copyOf(properties.values());
    }

    private List<FieldElement> beanPropertyFields(Set<BeanProperties.AccessKind> accessKinds) {
        if (accessKinds.contains(BeanProperties.AccessKind.FIELD)) {
            return getEnclosedElements(ElementQuery.ALL_FIELDS);
        }
        return List.of();
    }

    private static boolean matches(PropertyElementQuery propertyElementQuery, PropertyElement propertyElement) {
        Set<String> includes = propertyElementQuery.getIncludes();
        if (!includes.isEmpty() && !includes.contains(propertyElement.getName())) {
            return false;
        }
        if (propertyElementQuery.getExcludes().contains(propertyElement.getName())) {
            return false;
        }
        if (!propertyElementQuery.isAllowStaticProperties() && propertyElement.isStatic()) {
            return false;
        }
        if (propertyElementQuery.getVisibility() == BeanProperties.Visibility.PUBLIC && !propertyElement.isPublic()) {
            return false;
        }
        return propertyElementQuery.getExcludedAnnotations().stream().noneMatch(propertyElement::hasAnnotation);
    }

    private @Nullable PropertyElement mapBeanPropertyElement(AstBeanPropertiesUtils.BeanPropertyData value) {
        if (value.isExcluded) {
            return null;
        }
        return new ScalaPropertyElement(
            this,
            value.type,
            value.propertyName,
            value.getter,
            value.setter,
            value.field,
            accessKind(value.readAccessKind),
            accessKind(value.writeAccessKind),
            false,
            visitorContext
        );
    }

    private static PropertyElement.AccessKind accessKind(BeanProperties.AccessKind accessKind) {
        if (accessKind == BeanProperties.AccessKind.FIELD) {
            return PropertyElement.AccessKind.FIELD;
        }
        return PropertyElement.AccessKind.METHOD;
    }

    @Override
    public Optional<MethodElement> getPrimaryConstructor() {
        if (classData == null || classData.constructors().isEmpty()) {
            return Optional.empty();
        }
        if (classData.enumType()) {
            return classData.methods().stream()
                .filter(method -> "valueOf".equals(method.name()))
                .filter(method -> method.parameters().size() == 1)
                .filter(method -> String.class.getName().equals(method.parameters().get(0).type().name()))
                .findFirst()
                .map(this::methodElement);
        }
        return Optional.of(constructorElement(classData.constructors().get(0)));
    }

    @Override
    public Optional<MethodElement> getDefaultConstructor() {
        if (classData == null) {
            return Optional.empty();
        }
        if (classData.enumType()) {
            return Optional.empty();
        }
        return classData.constructors().stream()
            .filter(constructor -> constructor.parameters().isEmpty())
            .findFirst()
            .map(this::constructorElement);
    }

    @Override
    public <T extends Element> List<T> getEnclosedElements(ElementQuery<T> query) {
        if (classData == null) {
            return List.of();
        }
        ElementQuery.Result<T> result = query.result();
        List<Element> elements = new ArrayList<>();
        Class<T> elementType = result.getElementType();
        if (elementType == ConstructorElement.class) {
            classData.constructors().forEach(constructor -> elements.add(constructorElement(constructor)));
        } else if (elementType == MethodElement.class) {
            addMethodElements(result, elements);
        } else if (elementType == FieldElement.class) {
            addFieldElements(result, elements);
        } else if (elementType == PropertyElement.class) {
            classData.properties().forEach(property -> elements.add(propertyElement(property)));
        } else if (elementType == ClassElement.class) {
            elements.addAll(visitorContext.sourceClassElementsEnclosedBy(getName()));
        } else if (elementType == MemberElement.class) {
            addFieldElements(result, elements);
            addMethodElements(result, elements);
            if (!result.isExcludePropertyElements()) {
                classData.properties().forEach(property -> elements.add(propertyElement(property)));
            }
        }
        return elements.stream()
            .filter(element -> matches(result, element))
            .map(elementType::cast)
            .toList();
    }

    private <T extends Element> void addMethodElements(ElementQuery.Result<T> result, List<Element> elements) {
        ScalaClassData data = classData;
        if (data == null) {
            return;
        }
        Set<MethodSignature> signatures = new HashSet<>();
        data.methods().forEach(method -> addMethodElement(method, this, signatures, elements));
        if (!result.isOnlyDeclared()) {
            Set<String> visited = new HashSet<>();
            collectInheritedMethods(data.superType(), signatures, elements, visited);
            data.interfaces().forEach(interfaceType -> collectInheritedMethods(interfaceType, signatures, elements, visited));
        }
    }

    private void collectInheritedMethods(
        @Nullable ScalaTypeData type,
        Set<MethodSignature> signatures,
        List<Element> elements,
        Set<String> visited) {
        if (type == null || !visited.add(type.name())) {
            return;
        }
        Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(type.name());
        if (sourceElement.isEmpty()) {
            return;
        }
        ScalaClassElement inheritedElement = sourceElement.get();
        ScalaClassData inheritedData = inheritedElement.classData;
        if (inheritedData == null) {
            return;
        }
        Map<String, ScalaTypeData> substitutions = type.typeArguments();
        inheritedData.methods().forEach(method -> addMethodElement(substitute(method, substitutions), inheritedElement, signatures, elements));
        collectInheritedMethods(substitute(inheritedData.superType(), substitutions), signatures, elements, visited);
        inheritedData.interfaces().forEach(interfaceType -> collectInheritedMethods(substitute(interfaceType, substitutions), signatures, elements, visited));
    }

    private void addMethodElement(
        ScalaMethodData method,
        ScalaClassElement declaringElement,
        Set<MethodSignature> signatures,
        List<Element> elements) {
        if (signatures.add(signature(method))) {
            elements.add(declaringElement.methodElement(method));
        }
    }

    private MethodSignature signature(ScalaMethodData method) {
        return new MethodSignature(
            method.name(),
            method.parameters().stream()
                .map(parameter -> new TypeSignature(parameter.type().name(), parameter.type().arrayDimensions()))
                .toList()
        );
    }

    private ScalaMethodData substitute(ScalaMethodData method, Map<String, ScalaTypeData> substitutions) {
        if (substitutions.isEmpty()) {
            return method;
        }
        return new ScalaMethodData(
            method.name(),
            Objects.requireNonNull(substitute(method.returnType(), substitutions)),
            method.parameters().stream()
                .map(parameter -> substitute(parameter, substitutions))
                .toList(),
            method.typeParameters().stream()
                .map(typeParameter -> substitute(typeParameter, substitutions))
                .toList(),
            method.thrownTypes().stream()
                .map(thrownType -> substitute(thrownType, substitutions))
                .toList(),
            method.annotations(),
            method.modifiers(),
            method.constructor(),
            method.nativeType()
        );
    }

    private ScalaParameterData substitute(ScalaParameterData parameter, Map<String, ScalaTypeData> substitutions) {
        return new ScalaParameterData(
            parameter.name(),
            Objects.requireNonNull(substitute(parameter.type(), substitutions)),
            parameter.annotations(),
            parameter.nativeType()
        );
    }

    private @Nullable ScalaTypeData substitute(@Nullable ScalaTypeData type, Map<String, ScalaTypeData> substitutions) {
        if (type == null || substitutions.isEmpty()) {
            return type;
        }
        if (type.genericPlaceholder() && type.variableName() != null) {
            ScalaTypeData replacement = substitutions.get(type.variableName());
            if (replacement != null) {
                return type.arrayDimensions() == replacement.arrayDimensions()
                    ? replacement
                    : replacement.withArrayDimensions(type.arrayDimensions());
            }
        }
        Map<String, ScalaTypeData> typeArguments = substitute(type.typeArguments(), substitutions);
        ScalaTypeData superType = substitute(type.superType(), substitutions);
        List<ScalaTypeData> interfaces = substitute(type.interfaces(), substitutions);
        List<ScalaTypeData> bounds = substitute(type.bounds(), substitutions);
        List<ScalaTypeData> upperBounds = substitute(type.upperBounds(), substitutions);
        List<ScalaTypeData> lowerBounds = substitute(type.lowerBounds(), substitutions);
        if (typeArguments.equals(type.typeArguments())
            && Objects.equals(superType, type.superType())
            && interfaces.equals(type.interfaces())
            && bounds.equals(type.bounds())
            && upperBounds.equals(type.upperBounds())
            && lowerBounds.equals(type.lowerBounds())) {
            return type;
        }
        return new ScalaTypeData(
            type.name(),
            type.primitive(),
            type.arrayDimensions(),
            type.interfaceType(),
            typeArguments,
            superType,
            interfaces,
            type.annotations(),
            type.annotatedTypeUse(),
            type.nativeType(),
            type.genericPlaceholder(),
            type.variableName(),
            bounds,
            type.wildcard(),
            upperBounds,
            lowerBounds
        );
    }

    private Map<String, ScalaTypeData> substitute(Map<String, ScalaTypeData> types, Map<String, ScalaTypeData> substitutions) {
        if (types.isEmpty()) {
            return types;
        }
        Map<String, ScalaTypeData> substituted = new LinkedHashMap<>(types.size());
        boolean changed = false;
        for (Map.Entry<String, ScalaTypeData> entry : types.entrySet()) {
            ScalaTypeData original = entry.getValue();
            ScalaTypeData replacement = substitute(original, substitutions);
            substituted.put(entry.getKey(), replacement);
            changed |= !Objects.equals(replacement, original);
        }
        return changed ? substituted : types;
    }

    private List<ScalaTypeData> substitute(List<ScalaTypeData> types, Map<String, ScalaTypeData> substitutions) {
        if (types.isEmpty()) {
            return types;
        }
        List<ScalaTypeData> substituted = new ArrayList<>(types.size());
        boolean changed = false;
        for (ScalaTypeData type : types) {
            ScalaTypeData replacement = substitute(type, substitutions);
            substituted.add(replacement);
            changed |= !Objects.equals(replacement, type);
        }
        return changed ? substituted : types;
    }

    private <T extends Element> void addFieldElements(ElementQuery.Result<T> result, List<Element> elements) {
        ScalaClassData data = classData;
        if (data == null) {
            return;
        }
        data.fields().forEach(field -> {
            if (field.enumConstant()) {
                if (result.isIncludeEnumConstants() && this instanceof ScalaEnumElement enumElement) {
                    elements.add(enumElement.enumConstantElement(field));
                }
            } else {
                elements.add(fieldElement(field));
            }
        });
    }

    final ScalaConstructorElement constructorElement(ScalaMethodData constructor) {
        return constructorElements.computeIfAbsent(constructor, ignored -> new ScalaConstructorElement(this, constructor, visitorContext));
    }

    final ScalaMethodElement methodElement(ScalaMethodData method) {
        return methodElements.computeIfAbsent(method, ignored -> new ScalaMethodElement(this, method, visitorContext));
    }

    final ScalaFieldElement fieldElement(ScalaFieldData field) {
        return fieldElements.computeIfAbsent(field, ignored -> new ScalaFieldElement(this, field, visitorContext));
    }

    final ScalaEnumConstantElement enumConstantElement(ScalaFieldData field) {
        if (this instanceof ScalaEnumElement enumElement) {
            return enumConstantElements.computeIfAbsent(field, ignored -> new ScalaEnumConstantElement(enumElement, field, visitorContext));
        }
        throw new IllegalStateException("Declaring class must be a ScalaEnumElement");
    }

    final ScalaPropertyElement propertyElement(ScalaPropertyData property) {
        return propertyElements.computeIfAbsent(property, ignored -> new ScalaPropertyElement(this, property, visitorContext));
    }

    private <T extends Element> boolean matches(ElementQuery.Result<T> result, Element element) {
        if (result.isOnlyAbstract() && !element.isAbstract()) {
            return false;
        }
        if (result.isOnlyConcrete() && element.isAbstract()) {
            return false;
        }
        if (result.isOnlyStatic() && !element.isStatic()) {
            return false;
        }
        if (result.isOnlyInstance() && element.isStatic()) {
            return false;
        }
        if (result.isOnlyAccessible() && element instanceof MemberElement memberElement) {
            ClassElement fromType = result.getOnlyAccessibleFromType().orElse(this);
            if (!memberElement.isAccessible(fromType)) {
                return false;
            }
        }
        for (Predicate<String> predicate : result.getNamePredicates()) {
            if (!predicate.test(element.getName())) {
                return false;
            }
        }
        if (element instanceof io.micronaut.inject.ast.TypedElement typedElement) {
            for (Predicate<ClassElement> predicate : result.getTypePredicates()) {
                if (!predicate.test(typedElement.getType())) {
                    return false;
                }
            }
        }
        for (Predicate<AnnotationMetadata> predicate : result.getAnnotationPredicates()) {
            if (!predicate.test(element.getAnnotationMetadata())) {
                return false;
            }
        }
        for (Predicate<Set<ElementModifier>> predicate : result.getModifierPredicates()) {
            if (!predicate.test(element.getModifiers())) {
                return false;
            }
        }
        for (Predicate<T> predicate : result.getElementPredicates()) {
            if (!predicate.test(result.getElementType().cast(element))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getPackageName() {
        return NameUtils.getPackageName(getName());
    }

    @Override
    public PackageElement getPackage() {
        return new ScalaPackageElement(getPackageName(), visitorContext);
    }

    @Override
    public ClassElement withArrayDimensions(int arrayDimensions) {
        if (arrayDimensions == getArrayDimensions()) {
            return this;
        }
        return new ScalaClassElement(typeData.withArrayDimensions(arrayDimensions), visitorContext, getAnnotationMetadata());
    }

    @Override
    public int getArrayDimensions() {
        return typeData.arrayDimensions();
    }

    @Override
    public boolean isPrimitive() {
        return typeData.primitive();
    }

    @Override
    public boolean isContainerType() {
        return DefaultArgument.CONTAINER_TYPES.contains(getName())
            || getName().startsWith("scala.collection.");
    }

    @Override
    public ClassElement withTypeArguments(Map<String, ClassElement> typeArguments) {
        return this;
    }

    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        if (classData == null) {
            return new ScalaClassElement(typeData, visitorContext, annotationMetadata);
        }
        return new ScalaClassElement(classData, visitorContext, annotationMetadata);
    }

    @Override
    protected Class<?> equalityType() {
        return ScalaClassElement.class;
    }

    @Override
    protected Object equalityKey() {
        return new ClassElementKey(getName(), getArrayDimensions());
    }

    private record ClassElementKey(String name, int arrayDimensions) {
    }

    private record MethodSignature(String name, List<TypeSignature> parameterTypes) {
    }

    private record TypeSignature(String name, int arrayDimensions) {
    }
}
