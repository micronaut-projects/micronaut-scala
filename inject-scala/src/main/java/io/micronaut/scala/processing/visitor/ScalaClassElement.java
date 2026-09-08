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

import java.io.Serializable;
import java.util.Arrays;
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

    /**
     * Supertypes every Scala or Java class has, whose members the source path never produces
     * and which can carry no Micronaut metadata.
     */
    private static final Set<String> UNIVERSAL_SUPERTYPES = Set.of(
        Object.class.getName(),
        Enum.class.getName(),
        "java.io.Serializable",
        "scala.Any",
        "scala.AnyRef",
        "scala.Equals",
        "scala.Product",
        "scala.Serializable"
    );

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

    /**
     * A source class element re-parameterised, or re-dimensioned, without losing its
     * members. Copying through the {@code typeData}-only constructor drops
     * {@code classData}, and with it every method, field and property.
     */
    private ScalaClassElement(
        ScalaClassData classData,
        ScalaTypeData typeData,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata) {
        super(
            classData.name(),
            classData.nativeType(),
            classData.modifiers(),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.visitorContext = visitorContext;
        this.classData = classData;
        this.typeData = typeData;
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

    /**
     * Whether this element is assignable to the named type.
     *
     * <p>A type name carries no array dimensions -- an element for {@code Array[String]} is
     * named {@code java.lang.String} and reports one dimension separately -- so this used to
     * answer for the component type and call {@code Array[String]} assignable to
     * {@code String}, and {@code Array[Array[String]]} assignable to {@code Array[String]}.
     * Micronaut matches bean types, {@code @Requires} conditions and executable handlers
     * through this method, so an array bean satisfied an injection point for its component
     * type. An array is assignable by name only to the three types every array really is.</p>
     */
    @Override
    public boolean isAssignable(String type) {
        if (getArrayDimensions() > 0) {
            return Object.class.getName().equals(type)
                || Cloneable.class.getName().equals(type)
                || Serializable.class.getName().equals(type);
        }
        return isNameAssignable(type);
    }

    /**
     * Whether this element is assignable to the given element, which unlike a name can be an
     * array. Core's default delegates to {@link #isAssignable(String)}, which discards the
     * dimensions of both sides.
     */
    @Override
    public boolean isAssignable(ClassElement type) {
        int dimensions = type.getArrayDimensions();
        if (dimensions == 0) {
            return isAssignable(type.getName());
        }
        return dimensions == getArrayDimensions() && isNameAssignable(type.getName());
    }

    private boolean isNameAssignable(String type) {
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
        collectInheritedProperties(propertyElementQuery, properties, new HashSet<>());
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

    /**
     * Merges bean properties declared by source supertypes. Scala property accessors are filtered
     * out of the extracted method list and re-attached as {@link ScalaPropertyData}, so
     * {@link AstBeanPropertiesUtils} cannot rediscover an inherited property from its getter the
     * way the Java implementation does. Without this, a property declared on a Scala superclass or
     * on a trait (including a Scala 3 trait parameter) is invisible on the implementing class.
     *
     * @param propertyElementQuery The query
     * @param properties The properties collected so far, keyed by name; declared properties win
     * @param visited Guards against cycles in the type hierarchy
     */
    private void collectInheritedProperties(
        PropertyElementQuery propertyElementQuery,
        Map<String, PropertyElement> properties,
        Set<String> visited) {
        ScalaClassData data = classData;
        if (data == null) {
            return;
        }
        List<ScalaTypeData> supertypes = new ArrayList<>();
        if (data.superType() != null) {
            supertypes.add(data.superType());
        }
        supertypes.addAll(data.interfaces());
        for (ScalaTypeData supertype : supertypes) {
            if (!visited.add(supertype.name())) {
                continue;
            }
            Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(supertype.name());
            if (sourceElement.isEmpty()) {
                continue;
            }
            ScalaClassElement inherited = sourceElement.get();
            ScalaClassData inheritedData = inherited.classData;
            if (inheritedData == null) {
                continue;
            }
            Map<String, ScalaTypeData> substitutions = supertype.typeArguments();
            inheritedData.properties().stream()
                .map(property -> inherited.propertyElement(substitute(property, substitutions)))
                .filter(propertyElement -> matches(propertyElementQuery, propertyElement))
                .forEach(propertyElement -> properties.putIfAbsent(propertyElement.getName(), propertyElement));
            inherited.collectInheritedProperties(propertyElementQuery, properties, visited);
        }
    }

    /**
     * Resolves a supertype's declared property against that supertype's type arguments, so a
     * property declared as {@code T} on a generic parent is reported with the argument the subclass
     * supplies. Only the property type is substituted: the accessor methods are re-resolved through
     * the declaring element, which already substitutes them on the method path.
     *
     * @param property The inherited property
     * @param substitutions The supertype's type arguments
     * @return The resolved property
     */
    private ScalaPropertyData substitute(ScalaPropertyData property, Map<String, ScalaTypeData> substitutions) {
        ScalaTypeData resolvedType = substitute(property.type(), substitutions);
        if (substitutions.isEmpty() || resolvedType == null || resolvedType.equals(property.type())) {
            return property;
        }
        return new ScalaPropertyData(
            property.name(),
            resolvedType,
            property.readMethod(),
            property.writeMethod(),
            property.field(),
            property.annotations(),
            property.modifiers(),
            property.nativeType()
        );
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
            return enumValueOfMethod();
        }
        return Optional.of(constructorElement(classData.constructors().get(0)));
    }

    /**
     * The enum's value lookup method, validated the way Core's {@code BeanIntrospectionWriter}
     * validates it: static, non-private, exactly one {@code String}/{@code CharSequence} parameter,
     * and a return type assignable to the enum. Core turns an ill-formed method into a
     * ProcessingException raised from deep inside the introspection writer, so rejecting it here
     * instead means such a method is simply not a candidate.
     *
     * @return The lookup method, if the enum declares a valid one
     */
    final Optional<MethodElement> enumValueOfMethod() {
        if (classData == null) {
            return Optional.empty();
        }
        return classData.methods().stream()
            .filter(method -> "valueOf".equals(method.name()))
            .filter(method -> method.modifiers().contains(ElementModifier.STATIC))
            .filter(method -> !method.modifiers().contains(ElementModifier.PRIVATE))
            .filter(method -> method.parameters().size() == 1)
            .filter(method -> {
                String parameterType = method.parameters().get(0).type().name();
                return String.class.getName().equals(parameterType)
                    || CharSequence.class.getName().equals(parameterType);
            })
            .map(this::methodElement)
            .filter(method -> method.getReturnType().isAssignable(this))
            .findFirst()
            .map(MethodElement.class::cast);
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
            collectClasspathMethods(type.name(), signatures, elements);
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

    /**
     * Inherited members of a supertype that is not part of this compilation.
     *
     * <p>A supertype read from the classpath used to contribute nothing at all, so a class
     * extending a Java or already-compiled Scala base inherited none of its injectable
     * members -- no inherited {@code @Inject} method, no inherited bean property. The
     * classpath element walks its own hierarchy, so this does not recurse further.</p>
     *
     * <p>Universal supertypes are skipped. A Scala class on the classpath really does
     * implement {@code scala.Product}, {@code scala.Equals} and {@code java.io.Serializable},
     * but the source path never produces their members, and neither can carry Micronaut
     * metadata -- merging them in would make the two element kinds disagree about what a
     * class inherits.</p>
     */
    private void collectClasspathMethods(String name, Set<MethodSignature> signatures, List<Element> elements) {
        classpathElement(name).ifPresent(classpathElement -> {
            for (MethodElement method : classpathElement.getEnclosedElements(ElementQuery.ALL_METHODS)) {
                if (universalSupertype(method.getDeclaringType().getName())) {
                    continue;
                }
                if (signatures.add(signature(method))) {
                    elements.add(owned(method));
                }
            }
        });
    }

    private void collectClasspathFields(String name, Set<String> fieldNames, List<Element> elements) {
        classpathElement(name).ifPresent(classpathElement -> {
            for (FieldElement field : classpathElement.getEnclosedElements(ElementQuery.ALL_FIELDS)) {
                if (universalSupertype(field.getDeclaringType().getName())) {
                    continue;
                }
                if (fieldNames.add(field.getName())) {
                    elements.add(field);
                }
            }
        });
    }

    private Optional<ClassElement> classpathElement(String name) {
        if (universalSupertype(name)) {
            return Optional.empty();
        }
        return visitorContext.getClassElement(name);
    }

    private static boolean universalSupertype(String name) {
        return UNIVERSAL_SUPERTYPES.contains(name);
    }

    private MethodSignature signature(MethodElement method) {
        return new MethodSignature(
            method.getName(),
            Arrays.stream(method.getParameters())
                .map(parameter -> new TypeSignature(
                    parameter.getType().getName(),
                    parameter.getType().getArrayDimensions()))
                .toList()
        );
    }

    private void addMethodElement(
        ScalaMethodData method,
        ScalaClassElement declaringElement,
        Set<MethodSignature> signatures,
        List<Element> elements) {
        if (signatures.add(signature(method))) {
            elements.add(owned(declaringElement.methodElement(method)));
        }
    }

    /**
     * An inherited method owned by the class the query was made on, not by the supertype
     * that declares it.
     *
     * <p>{@code getDeclaringType()} answers where a method is declared; {@code
     * getOwningType()} answers which class it was reached through, and Core's Java module
     * builds every enclosed element -- inherited ones included -- owned by the queried
     * class. Both element kinds here kept the supertype as the owner, so anything that
     * resolves against the owner read the supertype's answer: an inherited accessor of a
     * {@code @ConfigurationProperties} class took its property prefix from the supertype,
     * which usually carries none, and bound {@code .host} instead of {@code app.host}.</p>
     */
    private MethodElement owned(MethodElement method) {
        return method.getOwningType().getName().equals(getName()) ? method : method.withNewOwningType(this);
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
        // Fields are de-duplicated by name, not signature: a subclass field shadows a
        // superclass field of the same name rather than overloading it.
        Set<String> fieldNames = new HashSet<>();
        addDeclaredFieldElements(this, data, result, fieldNames, elements);
        if (!result.isOnlyDeclared()) {
            // This walk did not exist, so inherited @Inject and @Value fields and inherited
            // @ConfigurationProperties state were always missed.
            Set<String> visited = new HashSet<>();
            visited.add(getName());
            collectInheritedFields(data.superType(), result, fieldNames, elements, visited);
            data.interfaces().forEach(interfaceType -> collectInheritedFields(interfaceType, result, fieldNames, elements, visited));
        }
    }

    private <T extends Element> void addDeclaredFieldElements(
        ScalaClassElement declaringElement,
        ScalaClassData data,
        ElementQuery.Result<T> result,
        Set<String> fieldNames,
        List<Element> elements) {
        data.fields().forEach(field -> {
            if (!fieldNames.add(field.name())) {
                return;
            }
            if (field.enumConstant()) {
                if (result.isIncludeEnumConstants() && declaringElement instanceof ScalaEnumElement enumElement) {
                    elements.add(enumElement.enumConstantElement(field));
                }
            } else {
                elements.add(declaringElement.fieldElement(field));
            }
        });
    }

    private <T extends Element> void collectInheritedFields(
        @Nullable ScalaTypeData type,
        ElementQuery.Result<T> result,
        Set<String> fieldNames,
        List<Element> elements,
        Set<String> visited) {
        if (type == null || !visited.add(type.name())) {
            return;
        }
        Optional<ScalaClassElement> sourceElement = visitorContext.sourceClassElement(type.name());
        if (sourceElement.isEmpty()) {
            collectClasspathFields(type.name(), fieldNames, elements);
            return;
        }
        ScalaClassElement inheritedElement = sourceElement.get();
        ScalaClassData inheritedData = inheritedElement.classData;
        if (inheritedData == null) {
            return;
        }
        Map<String, ScalaTypeData> substitutions = type.typeArguments();
        addDeclaredFieldElements(inheritedElement, substitute(inheritedData, substitutions), result, fieldNames, elements);
        collectInheritedFields(substitute(inheritedData.superType(), substitutions), result, fieldNames, elements, visited);
        inheritedData.interfaces().forEach(interfaceType ->
            collectInheritedFields(substitute(interfaceType, substitutions), result, fieldNames, elements, visited));
    }

    /**
     * A copy of the supertype's data with its field types resolved against the
     * parameterisation the subtype used, so an inherited {@code @Inject} field of type
     * {@code T} reports the concrete type at the injection point.
     */
    private ScalaClassData substitute(ScalaClassData data, Map<String, ScalaTypeData> substitutions) {
        if (substitutions.isEmpty()) {
            return data;
        }
        return new ScalaClassData(
            data.name(),
            data.annotations(),
            data.modifiers(),
            data.annotationType(),
            data.interfaceType(),
            data.enumType(),
            data.typeParameters(),
            data.superType(),
            data.interfaces(),
            data.constructors(),
            data.methods(),
            data.fields().stream().map(field -> substitute(field, substitutions)).toList(),
            data.properties(),
            data.enclosingTypeName(),
            data.nativeType()
        );
    }

    private ScalaFieldData substitute(ScalaFieldData field, Map<String, ScalaTypeData> substitutions) {
        return new ScalaFieldData(
            field.name(),
            Objects.requireNonNull(substitute(field.type(), substitutions)),
            field.annotations(),
            field.modifiers(),
            field.enumConstant(),
            field.constantValue(),
            field.nativeType()
        );
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
        return copy(typeData.withArrayDimensions(arrayDimensions), getAnnotationMetadata());
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
        if (typeArguments == null || typeArguments.isEmpty()) {
            return this;
        }
        // Micronaut calls this wherever it resolves generics -- `foldBoundGenericTypes`,
        // factory return types, `AstBeanPropertiesUtils`. Returning `this` handed the
        // caller back the unsubstituted type with no way to tell that nothing happened.
        Map<String, ScalaTypeData> resolved = new LinkedHashMap<>();
        typeArguments.forEach((name, element) -> resolved.put(name, typeDataOf(element)));
        return copy(typeData.withTypeArguments(resolved), getAnnotationMetadata());
    }

    private ClassElement copy(ScalaTypeData newTypeData, AnnotationMetadata annotationMetadata) {
        if (classData == null) {
            return new ScalaClassElement(newTypeData, visitorContext, annotationMetadata);
        }
        return new ScalaClassElement(classData, newTypeData, visitorContext, annotationMetadata);
    }

    /**
     * The {@link ScalaTypeData} behind a class element. Elements from outside this
     * compilation carry no Scala type data, so a minimal one is built from the Element API
     * itself; their native type is deliberately not borrowed, since only a dotty tree or
     * symbol is usable as one.
     */
    private static ScalaTypeData typeDataOf(ClassElement element) {
        if (element instanceof ScalaClassElement scalaClassElement) {
            return scalaClassElement.typeData;
        }
        return new ScalaTypeData(
            element.getName(),
            element.isPrimitive(),
            element.getArrayDimensions(),
            element.isInterface(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            false,
            null
        );
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

    /**
     * The identity of a class element: the type it names, not the compiler structure it was
     * read from.
     *
     * <p>Shared with {@link ScalaLoadedClassElement} so that a type read from source and the
     * same type read from the classpath are one element as far as Micronaut's element caches
     * are concerned. Placeholders and wildcards deliberately do not use it -- their identity
     * is the variable or the bounds, not the erasure.</p>
     *
     * @param name The type name
     * @param arrayDimensions The number of array dimensions
     */
    record ClassElementKey(String name, int arrayDimensions) {
    }

    private record MethodSignature(String name, List<TypeSignature> parameterTypes) {
    }

    private record TypeSignature(String name, int arrayDimensions) {
    }
}
