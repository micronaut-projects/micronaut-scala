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
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.ast.PropertyElementQuery;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.PropertyElementAnnotationMetadata;
import io.micronaut.inject.ast.utils.AstBeanPropertiesUtils;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reflection-backed classpath element used by the Scala visitor context.
 */
final class ScalaLoadedClassElement extends AbstractScalaElement implements ArrayableClassElement {

    private final Class<?> type;
    private final Class<?> componentType;
    private final ScalaVisitorContext visitorContext;
    private final Map<String, ClassElement> typeArguments;

    ScalaLoadedClassElement(Class<?> type, ScalaVisitorContext visitorContext) {
        this(type, visitorContext, ClasspathAnnotationMetadataReader.classMetadata(componentType(type)), Map.of());
    }

    private ScalaLoadedClassElement(
        Class<?> type,
        ScalaVisitorContext visitorContext,
        AnnotationMetadata annotationMetadata,
        Map<String, ClassElement> typeArguments) {
        super(
            componentType(type).getName(),
            type,
            javaModifiers(componentType(type).getModifiers()),
            MutableAnnotationMetadata.of(annotationMetadata),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
        this.type = type;
        this.componentType = componentType(type);
        this.visitorContext = visitorContext;
        this.typeArguments = Map.copyOf(typeArguments);
    }

    @Override
    public boolean isAssignable(String type) {
        if (getName().equals(type) || Object.class.getName().equals(type)) {
            return true;
        }
        try {
            return Class.forName(type, false, visitorContext.getProcessingClassLoader()).isAssignableFrom(this.type);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean isAssignable(Class<?> type) {
        return type.isAssignableFrom(this.type);
    }

    @Override
    public boolean isAssignable(ClassElement type) {
        return isAssignable(type.getName());
    }

    @Override
    public boolean isInterface() {
        return type.isInterface();
    }

    @Override
    public boolean isEnum() {
        return type.isEnum();
    }

    @Override
    public boolean isArray() {
        return type.isArray();
    }

    @Override
    public int getArrayDimensions() {
        return arrayDimensions(type);
    }

    @Override
    public boolean isPrimitive() {
        return componentType.isPrimitive();
    }

    @Override
    public boolean isContainerType() {
        return DefaultArgument.CONTAINER_TYPES.contains(getName())
            || getName().startsWith("scala.collection.");
    }

    @Override
    public Optional<ClassElement> getSuperType() {
        Class<?> superType = componentType.getSuperclass();
        if (superType == null) {
            return Optional.empty();
        }
        return Optional.of(new ScalaLoadedClassElement(superType, visitorContext));
    }

    @Override
    public Collection<ClassElement> getInterfaces() {
        return Arrays.stream(componentType.getInterfaces())
            .map(interfaceType -> new ScalaLoadedClassElement(interfaceType, visitorContext))
            .map(ClassElement.class::cast)
            .toList();
    }

    @Override
    public Map<String, ClassElement> getTypeArguments() {
        if (!typeArguments.isEmpty()) {
            return typeArguments;
        }
        Type[] typeParameters = componentType.getTypeParameters();
        if (typeParameters.length == 0) {
            return Map.of();
        }
        Map<String, ClassElement> typeArguments = new LinkedHashMap<>(typeParameters.length);
        for (Type typeParameter : typeParameters) {
            ClassElement typeArgument = classElement(typeParameter, Object.class);
            if (typeArgument instanceof GenericPlaceholderElement placeholderElement) {
                typeArguments.put(placeholderElement.getVariableName(), typeArgument);
            }
        }
        return typeArguments;
    }

    @Override
    public List<? extends GenericPlaceholderElement> getDeclaredGenericPlaceholders() {
        return Arrays.stream(componentType.getTypeParameters())
            .map(typeParameter -> (GenericPlaceholderElement) ClassElement.of(typeParameter))
            .toList();
    }

    @Override
    public Optional<MethodElement> getPrimaryConstructor() {
        return Arrays.stream(componentType.getDeclaredConstructors())
            .filter(constructor -> !constructor.isSynthetic())
            .min(Comparator
                .comparing((Constructor<?> constructor) -> !Modifier.isPublic(constructor.getModifiers()))
                .thenComparingInt(Constructor::getParameterCount))
            .map(this::constructorElement)
            .map(MethodElement.class::cast);
    }

    @Override
    public Optional<MethodElement> getDefaultConstructor() {
        try {
            return Optional.of(constructorElement(componentType.getDeclaredConstructor()));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }
    }

    @Override
    public <T extends Element> List<T> getEnclosedElements(ElementQuery<T> query) {
        ElementQuery.Result<T> result = query.result();
        Class<T> elementType = result.getElementType();
        List<Element> elements = new ArrayList<>();
        if (elementType == ConstructorElement.class) {
            Constructor<?>[] constructors = result.isOnlyDeclared() ? componentType.getDeclaredConstructors() : componentType.getConstructors();
            Arrays.stream(constructors).map(this::constructorElement).forEach(elements::add);
        } else if (elementType == MethodElement.class) {
            Method[] methods = result.isOnlyDeclared() ? componentType.getDeclaredMethods() : componentType.getMethods();
            Arrays.stream(methods).map(this::methodElement).forEach(elements::add);
        } else if (elementType == FieldElement.class) {
            Field[] fields = result.isOnlyDeclared() ? componentType.getDeclaredFields() : componentType.getFields();
            Arrays.stream(fields).map(this::fieldElement).forEach(elements::add);
        } else if (elementType == MemberElement.class) {
            Field[] fields = result.isOnlyDeclared() ? componentType.getDeclaredFields() : componentType.getFields();
            Method[] methods = result.isOnlyDeclared() ? componentType.getDeclaredMethods() : componentType.getMethods();
            Arrays.stream(fields).map(this::fieldElement).forEach(elements::add);
            Arrays.stream(methods).map(this::methodElement).forEach(elements::add);
        }
        return elements.stream()
            .filter(element -> matches(result, element))
            .map(elementType::cast)
            .toList();
    }

    @Override
    public List<PropertyElement> getBeanProperties() {
        return getBeanProperties(PropertyElementQuery.of(getAnnotationMetadata()));
    }

    @Override
    public List<PropertyElement> getBeanProperties(PropertyElementQuery propertyElementQuery) {
        return AstBeanPropertiesUtils.resolveBeanProperties(
            propertyElementQuery,
            this,
            () -> getEnclosedElements(ElementQuery.ALL_METHODS),
            () -> getEnclosedElements(ElementQuery.ALL_FIELDS),
            false,
            Collections.emptySet(),
            methodElement -> Optional.empty(),
            methodElement -> Optional.empty(),
            this::mapBeanPropertyElement
        );
    }

    private @Nullable PropertyElement mapBeanPropertyElement(AstBeanPropertiesUtils.BeanPropertyData value) {
        if (value.isExcluded) {
            return null;
        }
        return new LoadedPropertyElement(
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
    public ClassElement withArrayDimensions(int arrayDimensions) {
        if (arrayDimensions == getArrayDimensions()) {
            return this;
        }
        if (arrayDimensions == 0) {
            return new ScalaLoadedClassElement(componentType, visitorContext, getAnnotationMetadata(), typeArguments);
        }
        int[] dimensions = new int[arrayDimensions];
        Class<?> arrayType = Array.newInstance(componentType, dimensions).getClass();
        return new ScalaLoadedClassElement(arrayType, visitorContext, getAnnotationMetadata(), typeArguments);
    }

    @Override
    public ClassElement withTypeArguments(Map<String, ClassElement> typeArguments) {
        return new ScalaLoadedClassElement(type, visitorContext, getAnnotationMetadata(), typeArguments);
    }

    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaLoadedClassElement(type, visitorContext, annotationMetadata, typeArguments);
    }

    private LoadedMethodElement methodElement(Method method) {
        return new LoadedMethodElement(this, this, method, parameters(method), visitorContext, ClasspathAnnotationMetadataReader.methodMetadata(method));
    }

    private LoadedConstructorElement constructorElement(Constructor<?> constructor) {
        return new LoadedConstructorElement(this, this, constructor, parameters(constructor), visitorContext, ClasspathAnnotationMetadataReader.constructorMetadata(constructor));
    }

    private LoadedFieldElement fieldElement(Field field) {
        return new LoadedFieldElement(
            this,
            this,
            field,
            classElement(field.getType(), visitorContext),
            classElement(field.getGenericType(), field.getType(), visitorContext),
            visitorContext,
            ClasspathAnnotationMetadataReader.fieldMetadata(field)
        );
    }

    private ParameterElement[] parameters(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        Type[] genericParameterTypes = executable.getGenericParameterTypes();
        Class<?>[] parameterTypes = executable.getParameterTypes();
        ParameterElement[] parameterElements = new ParameterElement[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            ClassElement type = classElement(parameterTypes[i], visitorContext);
            ClassElement genericType = classElement(genericParameterTypes[i], parameterTypes[i], visitorContext);
            parameterElements[i] = new LoadedParameterElement(
                type,
                genericType,
                parameters[i],
                parameters[i].getName(),
                visitorContext,
                ClasspathAnnotationMetadataReader.parameterMetadata(executable, i)
            );
        }
        return parameterElements;
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
        if (!result.isIncludeHiddenElements() && element.isSynthetic()) {
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
        if (element instanceof TypedElement typedElement) {
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

    private static ClassElement classElement(Type genericType, Class<?> erasedType) {
        return classElement(genericType, erasedType, null);
    }

    private static ClassElement classElement(Type genericType, Class<?> erasedType, @Nullable ScalaVisitorContext visitorContext) {
        try {
            if (genericType instanceof Class<?> genericClass) {
                return classElement(genericClass, visitorContext);
            }
            if (genericType instanceof ParameterizedType parameterizedType && visitorContext != null) {
                return classElement(erasedType, visitorContext)
                    .withTypeArguments(typeArguments(erasedType, parameterizedType, visitorContext));
            }
            return ClassElement.of(genericType);
        } catch (RuntimeException ignored) {
            return classElement(erasedType, visitorContext);
        }
    }

    private static Map<String, ClassElement> typeArguments(
        Class<?> erasedType,
        ParameterizedType parameterizedType,
        ScalaVisitorContext visitorContext) {
        TypeVariable<?>[] typeVariables = erasedType.getTypeParameters();
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        if (typeVariables.length == 0 || actualTypeArguments.length == 0) {
            return Map.of();
        }
        Map<String, ClassElement> resolved = new LinkedHashMap<>(Math.min(typeVariables.length, actualTypeArguments.length));
        for (int i = 0; i < typeVariables.length && i < actualTypeArguments.length; i++) {
            Type actualType = actualTypeArguments[i];
            resolved.put(
                typeVariables[i].getName(),
                classElement(actualType, erasedClass(actualType, Object.class), visitorContext)
            );
        }
        return resolved;
    }

    private static Class<?> erasedClass(Type type, Class<?> fallback) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof TypeVariable<?> typeVariable && typeVariable.getBounds().length > 0) {
            return erasedClass(typeVariable.getBounds()[0], fallback);
        }
        if (type instanceof WildcardType wildcardType && wildcardType.getUpperBounds().length > 0) {
            return erasedClass(wildcardType.getUpperBounds()[0], fallback);
        }
        if (type instanceof GenericArrayType genericArrayType) {
            return Array.newInstance(erasedClass(genericArrayType.getGenericComponentType(), Object.class), 0).getClass();
        }
        return fallback;
    }

    private static ClassElement classElement(Class<?> type, @Nullable ScalaVisitorContext visitorContext) {
        if (!type.isPrimitive()) {
            return visitorContext == null ? ClassElement.of(type) : new ScalaLoadedClassElement(type, visitorContext);
        }
        return switch (type.getName()) {
            case "boolean" -> PrimitiveElement.BOOLEAN;
            case "byte" -> PrimitiveElement.BYTE;
            case "char" -> PrimitiveElement.CHAR;
            case "double" -> PrimitiveElement.DOUBLE;
            case "float" -> PrimitiveElement.FLOAT;
            case "int" -> PrimitiveElement.INT;
            case "long" -> PrimitiveElement.LONG;
            case "short" -> PrimitiveElement.SHORT;
            case "void" -> PrimitiveElement.VOID;
            default -> ClassElement.of(type);
        };
    }

    private static Class<?> componentType(Class<?> type) {
        Class<?> componentType = type;
        while (componentType.isArray()) {
            componentType = componentType.getComponentType();
        }
        return componentType;
    }

    private static int arrayDimensions(Class<?> type) {
        int dimensions = 0;
        Class<?> componentType = type;
        while (componentType.isArray()) {
            dimensions++;
            componentType = componentType.getComponentType();
        }
        return dimensions;
    }

    private static Set<ElementModifier> javaModifiers(int modifiers) {
        EnumSet<ElementModifier> elementModifiers = EnumSet.noneOf(ElementModifier.class);
        if (Modifier.isPublic(modifiers)) {
            elementModifiers.add(ElementModifier.PUBLIC);
        }
        if (Modifier.isProtected(modifiers)) {
            elementModifiers.add(ElementModifier.PROTECTED);
        }
        if (Modifier.isPrivate(modifiers)) {
            elementModifiers.add(ElementModifier.PRIVATE);
        }
        if (Modifier.isAbstract(modifiers)) {
            elementModifiers.add(ElementModifier.ABSTRACT);
        }
        if (Modifier.isStatic(modifiers)) {
            elementModifiers.add(ElementModifier.STATIC);
        }
        if (Modifier.isFinal(modifiers)) {
            elementModifiers.add(ElementModifier.FINAL);
        }
        if (Modifier.isTransient(modifiers)) {
            elementModifiers.add(ElementModifier.TRANSIENT);
        }
        if (Modifier.isVolatile(modifiers)) {
            elementModifiers.add(ElementModifier.VOLATILE);
        }
        if (Modifier.isSynchronized(modifiers)) {
            elementModifiers.add(ElementModifier.SYNCHRONIZED);
        }
        if (Modifier.isNative(modifiers)) {
            elementModifiers.add(ElementModifier.NATIVE);
        }
        if (Modifier.isStrict(modifiers)) {
            elementModifiers.add(ElementModifier.STRICTFP);
        }
        return Set.copyOf(elementModifiers);
    }

    private static boolean isPackagePrivate(int modifiers) {
        return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers) && !Modifier.isPrivate(modifiers);
    }

    private static final class LoadedConstructorElement extends AbstractScalaElement implements ConstructorElement {

        private final ClassElement owningType;
        private final ClassElement declaringType;
        private final Constructor<?> constructor;
        private final ParameterElement[] parameters;
        private final ScalaVisitorContext visitorContext;

        private LoadedConstructorElement(
            ClassElement owningType,
            ClassElement declaringType,
            Constructor<?> constructor,
            ParameterElement[] parameters,
            ScalaVisitorContext visitorContext,
            AnnotationMetadata annotationMetadata) {
            super(
                "<init>",
                constructor,
                javaModifiers(constructor.getModifiers()),
                MutableAnnotationMetadata.of(annotationMetadata),
                visitorContext.getScalaAnnotationMetadataBuilder()
            );
            this.owningType = owningType;
            this.declaringType = declaringType;
            this.constructor = constructor;
            this.parameters = parameters;
            this.visitorContext = visitorContext;
        }

        @Override
        public ParameterElement[] getParameters() {
            return parameters;
        }

        @Override
        public MethodElement withParameters(ParameterElement... newParameters) {
            return new LoadedConstructorElement(owningType, declaringType, constructor, newParameters, visitorContext, getAnnotationMetadata());
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public ClassElement getOwningType() {
            return owningType;
        }

        @Override
        public boolean isSynthetic() {
            return constructor.isSynthetic();
        }

        @Override
        public boolean isVarArgs() {
            return constructor.isVarArgs();
        }

        @Override
        public boolean isPackagePrivate() {
            return ScalaLoadedClassElement.isPackagePrivate(constructor.getModifiers());
        }

        @Override
        public MethodElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
            return new LoadedConstructorElement(owningType, declaringType, constructor, parameters, visitorContext, annotationMetadata);
        }
    }

    private static final class LoadedMethodElement extends AbstractScalaElement implements MethodElement {

        private final ClassElement owningType;
        private final ClassElement declaringType;
        private final Method method;
        private final ParameterElement[] parameters;
        private final ScalaVisitorContext visitorContext;

        private LoadedMethodElement(
            ClassElement owningType,
            ClassElement declaringType,
            Method method,
            ParameterElement[] parameters,
            ScalaVisitorContext visitorContext,
            AnnotationMetadata annotationMetadata) {
            super(
                method.getName(),
                method,
                javaModifiers(method.getModifiers()),
                MutableAnnotationMetadata.of(annotationMetadata),
                visitorContext.getScalaAnnotationMetadataBuilder()
            );
            this.owningType = owningType;
            this.declaringType = declaringType;
            this.method = method;
            this.parameters = parameters;
            this.visitorContext = visitorContext;
        }

        @Override
        public ClassElement getReturnType() {
            return classElement(method.getReturnType(), visitorContext);
        }

        @Override
        public ClassElement getGenericReturnType() {
            return classElement(method.getGenericReturnType(), method.getReturnType(), visitorContext);
        }

        @Override
        public ParameterElement[] getParameters() {
            return parameters;
        }

        @Override
        public MethodElement withParameters(ParameterElement... newParameters) {
            return new LoadedMethodElement(owningType, declaringType, method, newParameters, visitorContext, getAnnotationMetadata());
        }

        @Override
        public MethodElement withNewOwningType(ClassElement owningType) {
            return new LoadedMethodElement(owningType, declaringType, method, parameters, visitorContext, getAnnotationMetadata());
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public ClassElement getOwningType() {
            return owningType;
        }

        @Override
        public boolean isSynthetic() {
            return method.isSynthetic() || method.isBridge();
        }

        @Override
        public boolean isDefault() {
            return method.isDefault();
        }

        @Override
        public boolean isVarArgs() {
            return method.isVarArgs();
        }

        @Override
        public boolean isPackagePrivate() {
            int modifiers = method.getModifiers();
            return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers) && !Modifier.isPrivate(modifiers);
        }

        @Override
        public MethodElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
            return new LoadedMethodElement(owningType, declaringType, method, parameters, visitorContext, annotationMetadata);
        }
    }

    private static final class LoadedFieldElement extends AbstractScalaElement implements FieldElement {

        private final ClassElement owningType;
        private final ClassElement declaringType;
        private final Field field;
        private final ClassElement type;
        private final ClassElement genericType;
        private final ScalaVisitorContext visitorContext;

        private LoadedFieldElement(
            ClassElement owningType,
            ClassElement declaringType,
            Field field,
            ClassElement type,
            ClassElement genericType,
            ScalaVisitorContext visitorContext,
            AnnotationMetadata annotationMetadata) {
            super(
                field.getName(),
                field,
                javaModifiers(field.getModifiers()),
                MutableAnnotationMetadata.of(annotationMetadata),
                visitorContext.getScalaAnnotationMetadataBuilder()
            );
            this.owningType = owningType;
            this.declaringType = declaringType;
            this.field = field;
            this.type = type;
            this.genericType = genericType;
            this.visitorContext = visitorContext;
        }

        @Override
        public ClassElement getType() {
            return type;
        }

        @Override
        public ClassElement getGenericType() {
            return genericType;
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public ClassElement getOwningType() {
            return owningType;
        }

        @Override
        public boolean isSynthetic() {
            return field.isSynthetic();
        }

        @Override
        public boolean isPackagePrivate() {
            return ScalaLoadedClassElement.isPackagePrivate(field.getModifiers());
        }

        @Override
        public FieldElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
            return new LoadedFieldElement(owningType, declaringType, field, type, genericType, visitorContext, annotationMetadata);
        }
    }

    private static final class LoadedParameterElement extends AbstractScalaElement implements ParameterElement {

        private final ClassElement type;
        private final ClassElement genericType;
        private final ScalaVisitorContext visitorContext;

        private LoadedParameterElement(
            ClassElement type,
            ClassElement genericType,
            Parameter parameter,
            String name,
            ScalaVisitorContext visitorContext,
            AnnotationMetadata annotationMetadata) {
            super(
                name,
                parameter,
                Set.of(ElementModifier.PUBLIC),
                MutableAnnotationMetadata.of(annotationMetadata),
                visitorContext.getScalaAnnotationMetadataBuilder()
            );
            this.type = type;
            this.genericType = genericType;
            this.visitorContext = visitorContext;
        }

        @Override
        public ClassElement getType() {
            return type;
        }

        @Override
        public ClassElement getGenericType() {
            return genericType;
        }

        @Override
        public ParameterElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
            return new LoadedParameterElement(
                type,
                genericType,
                (Parameter) getNativeType(),
                getName(),
                visitorContext,
                annotationMetadata
            );
        }
    }

    private static final class LoadedPropertyElement extends AbstractScalaElement implements PropertyElement {

        private final ClassElement declaringType;
        private final ClassElement type;
        private final @Nullable MethodElement readMethod;
        private final @Nullable MethodElement writeMethod;
        private final @Nullable FieldElement field;
        private final AccessKind readAccessKind;
        private final AccessKind writeAccessKind;
        private final boolean excluded;
        private final ElementAnnotationMetadata annotationMetadata;

        private LoadedPropertyElement(
            ClassElement declaringType,
            ClassElement type,
            String name,
            @Nullable MethodElement readMethod,
            @Nullable MethodElement writeMethod,
            @Nullable FieldElement field,
            AccessKind readAccessKind,
            AccessKind writeAccessKind,
            boolean excluded,
            ScalaVisitorContext visitorContext) {
            super(
                name,
                selectNativeType(readMethod, writeMethod, field),
                selectModifiers(readMethod, writeMethod, field),
                MutableAnnotationMetadata.of(AnnotationMetadata.EMPTY_METADATA),
                visitorContext.getScalaAnnotationMetadataBuilder()
            );
            this.declaringType = declaringType;
            this.type = type;
            this.readMethod = readMethod;
            this.writeMethod = writeMethod;
            this.field = field;
            this.readAccessKind = readAccessKind;
            this.writeAccessKind = writeAccessKind;
            this.excluded = excluded;
            this.annotationMetadata = new PropertyElementAnnotationMetadata(
                this,
                readMethod,
                writeMethod,
                field,
                null,
                AnnotationMetadata.EMPTY_METADATA,
                true
            );
        }

        @Override
        public ClassElement getType() {
            return type;
        }

        @Override
        public Optional<FieldElement> getField() {
            return Optional.ofNullable(field);
        }

        @Override
        public Optional<MethodElement> getWriteMethod() {
            return Optional.ofNullable(writeMethod);
        }

        @Override
        public Optional<MethodElement> getReadMethod() {
            return Optional.ofNullable(readMethod);
        }

        @Override
        public AccessKind getReadAccessKind() {
            return readAccessKind;
        }

        @Override
        public AccessKind getWriteAccessKind() {
            return writeAccessKind;
        }

        @Override
        public boolean isExcluded() {
            return excluded;
        }

        @Override
        public ClassElement getDeclaringType() {
            return declaringType;
        }

        @Override
        public ClassElement getOwningType() {
            return declaringType;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return annotationMetadata.getAnnotationMetadata();
        }

        private static Object selectNativeType(
            @Nullable MethodElement readMethod,
            @Nullable MethodElement writeMethod,
            @Nullable FieldElement field) {
            if (readMethod != null) {
                return readMethod.getNativeType();
            }
            if (writeMethod != null) {
                return writeMethod.getNativeType();
            }
            return field == null ? Object.class : field.getNativeType();
        }

        private static Set<ElementModifier> selectModifiers(
            @Nullable MethodElement readMethod,
            @Nullable MethodElement writeMethod,
            @Nullable FieldElement field) {
            if (readMethod != null) {
                return readMethod.getModifiers();
            }
            if (writeMethod != null) {
                return writeMethod.getModifiers();
            }
            return field == null ? Set.of(ElementModifier.PUBLIC) : field.getModifiers();
        }
    }
}
