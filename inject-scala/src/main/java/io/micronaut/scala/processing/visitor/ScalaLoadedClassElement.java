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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ArrayableClassElement;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.WildcardElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PrimitiveElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.ast.PropertyElementQuery;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MethodElementAnnotationMetadata;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
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
import java.util.HashSet;
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

    private static final int MAX_SUBSTITUTION_DEPTH = 16;

    private final Class<?> type;
    private final Class<?> componentType;
    private final ScalaVisitorContext visitorContext;
    private final Map<String, ClassElement> typeArguments;

    ScalaLoadedClassElement(Class<?> type, ScalaVisitorContext visitorContext) {
        this(type, visitorContext, loadedMetadata(componentType(type).getName(), componentType(type),
            ClasspathAnnotationMetadataReader.classAnnotations(componentType(type)), visitorContext), Map.of());
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

    /**
     * Whether this element is assignable to the named type.
     *
     * <p>{@code getName()} is the component type's name -- an element for {@code String[]} is
     * named {@code java.lang.String} and reports its dimensions separately -- so the
     * short-circuit answered for the component and called an array assignable to the type it
     * is an array of. The reflective check below is already dimension-aware, so it only has
     * to be reached.</p>
     */
    @Override
    public boolean isAssignable(String type) {
        if (Object.class.getName().equals(type)) {
            return true;
        }
        if (getArrayDimensions() == 0) {
            return isComponentAssignable(type);
        }
        // `this.type` is the array class here, so the reflective check answers for the array
        // itself: true for Cloneable and Serializable, false for the component type.
        return isAssignableFrom(type, this.type);
    }

    private boolean isComponentAssignable(String type) {
        if (getName().equals(type)) {
            return true;
        }
        return isAssignableFrom(type, componentType);
    }

    private boolean isAssignableFrom(String type, Class<?> candidate) {
        try {
            return Class.forName(type, false, visitorContext.getProcessingClassLoader()).isAssignableFrom(candidate);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean isAssignable(Class<?> type) {
        return type.isAssignableFrom(this.type);
    }

    /**
     * Core's default delegates to {@link #isAssignable(String)}, which cannot express the
     * dimensions of either side.
     */
    @Override
    public boolean isAssignable(ClassElement type) {
        int dimensions = type.getArrayDimensions();
        if (dimensions == 0) {
            return isAssignable(type.getName());
        }
        // Arrays are assignable only to arrays of the same rank, and then covariantly in
        // the component type -- `String[]` is a `CharSequence[]`, as in Java.
        return dimensions == getArrayDimensions() && isComponentAssignable(type.getName());
    }

    /**
     * The source-level name, with nested types separated by a dot, as the Java module answers.
     * Core's default returns the binary name.
     */
    @Override
    public String getCanonicalName() {
        String canonicalName = componentType.getCanonicalName();
        if (canonicalName != null) {
            return canonicalName;
        }
        // Null for an anonymous or local class, which has no source-level name to give. Core's
        // default, which cannot be reached with `super` from here.
        if (isOptional()) {
            return getFirstTypeArgument().map(ClassElement::getName).orElse(Object.class.getName());
        }
        return getName();
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
        return ScalaContainerTypes.isContainerType(this);
    }

    @Override
    public Map<String, Map<String, ClassElement>> getAllTypeArguments() {
        return ScalaContainerTypes.withIterableTypeArguments(
            this, ArrayableClassElement.super.getAllTypeArguments());
    }

    @Override
    public Optional<ClassElement> getSuperType() {
        Class<?> superType = componentType.getSuperclass();
        if (superType == null) {
            return Optional.empty();
        }
        return Optional.of(supertypeElement(componentType.getGenericSuperclass(), superType));
    }

    @Override
    public Collection<ClassElement> getInterfaces() {
        Class<?>[] erasedInterfaces = componentType.getInterfaces();
        Type[] genericInterfaces = componentType.getGenericInterfaces();
        List<ClassElement> interfaces = new ArrayList<>(erasedInterfaces.length);
        for (int i = 0; i < erasedInterfaces.length; i++) {
            Type genericInterface = i < genericInterfaces.length ? genericInterfaces[i] : erasedInterfaces[i];
            interfaces.add(supertypeElement(genericInterface, erasedInterfaces[i]));
        }
        return interfaces;
    }

    /**
     * A supertype of this type, with its type arguments resolved.
     *
     * <p>Two things have to happen for {@code getAllTypeArguments()} to answer "what is
     * {@code T} for the {@code EventListener<T>} this type implements". The supertype has
     * to be read from the generic signature rather than from the erased
     * {@code getSuperclass()} / {@code getInterfaces()}, or a bound argument is simply not
     * there. And any argument the supertype states as a type variable of *this* type has
     * to be substituted for what this element binds it to, or the answer stops at the
     * first link in the chain.</p>
     *
     * @param genericType The supertype as declared, which may be parameterized
     * @param erasedType The supertype's erasure
     * @return The supertype element
     */
    private ClassElement supertypeElement(Type genericType, Class<?> erasedType) {
        ClassElement supertype = classElement(genericType, erasedType, visitorContext);
        if (typeArguments.isEmpty()) {
            return supertype;
        }
        Map<String, ClassElement> declared = supertype.getTypeArguments();
        if (declared.isEmpty()) {
            return supertype;
        }
        Map<String, ClassElement> substituted = new LinkedHashMap<>(declared.size());
        declared.forEach((name, argument) -> substituted.put(name, substitute(argument)));
        return supertype.withTypeArguments(substituted);
    }

    /**
     * Replaces a type variable of this class with the argument it was resolved at.
     *
     * <p>A classpath member is built from reflection, which knows the declaration and not the
     * use: {@code Function.apply} reads as {@code Object apply(Object)} however the
     * {@code Function} was parameterized. The type arguments were being applied to supertypes
     * but not to members, so a factory producing {@code Function[String, Integer]} wrote a
     * definition whose recorded arguments said {@code T=String, R=Integer} while its one
     * executable method still took and returned {@code Object} -- and a caller looking the
     * method up by the types the definition itself reports found nothing.</p>
     */
    private ClassElement substitute(ClassElement argument) {
        if (argument instanceof GenericPlaceholderElement placeholder) {
            ClassElement bound = typeArguments.get(placeholder.getVariableName());
            if (bound != null) {
                return bound;
            }
        }
        return argument;
    }

    /**
     * Resolves a type, including the variables nested inside it.
     *
     * <p>A repository returns {@code List<E>} far more often than a bare {@code E}, and it
     * declares its own variables in terms of the class's -- {@code <S extends E> S save(S)} --
     * so neither the arguments of the type nor the bound of the variable can be left alone.</p>
     *
     * <p>Deliberately not what {@link #substitute} does when resolving a supertype. Applying
     * the same depth there changes the interface an {@code @Adapter} is generated against, and
     * the adapted method is then resolved by argument types that no longer match: the listener
     * for one event ends up dispatching into the method written for another.</p>
     *
     * @param argument The declared type
     * @param depth Guards a variable bounded, however indirectly, by itself
     * @return The return type with this element's bindings applied
     */
    private ClassElement substituteDeep(ClassElement argument, int depth) {
        if (depth > MAX_SUBSTITUTION_DEPTH) {
            return argument;
        }
        if (argument instanceof GenericPlaceholderElement placeholder) {
            ClassElement bound = typeArguments.get(placeholder.getVariableName());
            if (bound != null) {
                return bound;
            }
            List<? extends ClassElement> bounds = placeholder.getBounds();
            if (!bounds.isEmpty()) {
                ClassElement declaredBound = bounds.get(0);
                ClassElement resolved = substituteDeep(declaredBound, depth + 1);
                // Only when following the bound reached something this element actually binds.
                // A variable of a type used raw -- the interface an `@Adapter` names -- is
                // genuinely unbound, and collapsing it to its bound rewrites the signature the
                // adapter is generated against.
                if (resolved != declaredBound) {
                    return resolved;
                }
            }
            return argument;
        }
        if (argument instanceof WildcardElement wildcard) {
            // `? extends E` is an E once E is known. The bound is what every caller reads --
            // what the collection holds -- so the resolved bound stands in for the wildcard.
            List<? extends ClassElement> upperBounds = wildcard.getUpperBounds();
            if (!upperBounds.isEmpty()) {
                ClassElement declaredBound = upperBounds.get(0);
                ClassElement resolved = substituteDeep(declaredBound, depth + 1);
                if (resolved != declaredBound) {
                    return resolved;
                }
            }
            return argument;
        }
        Map<String, ClassElement> arguments = argument.getTypeArguments();
        if (arguments.isEmpty()) {
            return argument;
        }
        Map<String, ClassElement> substituted = new LinkedHashMap<>(arguments.size());
        boolean changed = false;
        for (Map.Entry<String, ClassElement> entry : arguments.entrySet()) {
            ClassElement resolved = substituteDeep(entry.getValue(), depth + 1);
            changed |= resolved != entry.getValue();
            substituted.put(entry.getKey(), resolved);
        }
        return changed ? argument.withTypeArguments(substituted) : argument;
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
            // The erasure of a type variable is its first bound, not Object:
            // `java.lang.Enum<E extends Enum<E>>` erases E to `java.lang.Enum`. Passing
            // Object.class made every classpath type variable report `java.lang.Object`,
            // which is what a generated bean definition would then bind against.
            ClassElement typeArgument = classElement(
                typeParameter, erasedClass(typeParameter, Object.class), visitorContext);
            if (typeArgument instanceof GenericPlaceholderElement placeholderElement) {
                typeArguments.put(placeholderElement.getVariableName(), typeArgument);
            }
        }
        return typeArguments;
    }

    @Override
    public List<? extends GenericPlaceholderElement> getDeclaredGenericPlaceholders() {
        // Deliberately not `ClassElement.of(...)`, for the reasons given on `classElement`.
        return Arrays.stream(componentType.getTypeParameters())
            .map(typeParameter -> placeholderElement(
                typeParameter, erasedClass(typeParameter, Object.class), visitorContext))
            .map(GenericPlaceholderElement.class::cast)
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
            // `getDeclaredConstructors()` rather than `getConstructors()`, which would hide the
            // non-public ones. A constructor is not inherited by the JVM, but
            // `ElementQuery.CONSTRUCTORS` is defined as `of(ConstructorElement).onlyDeclared()`,
            // so a query without that flag is asking for the superclass's too.
            for (Class<?> current : hierarchy(result.isOnlyDeclared())) {
                ClassElement declaring = declaringElement(current);
                for (Constructor<?> constructor : current.getDeclaredConstructors()) {
                    elements.add(constructorElement(constructor, declaring));
                }
            }
        } else if (elementType == MethodElement.class) {
            collectMethods(result, elements);
        } else if (elementType == FieldElement.class) {
            collectFields(result, elements);
        } else if (elementType == PropertyElement.class) {
            elements.addAll(getBeanProperties());
        } else if (elementType == ClassElement.class) {
            Arrays.stream(componentType.getDeclaredClasses())
                .map(nested -> new ScalaLoadedClassElement(nested, visitorContext))
                .forEach(elements::add);
        } else if (elementType == MemberElement.class) {
            collectFields(result, elements);
            collectMethods(result, elements);
            if (!result.isExcludePropertyElements()) {
                elements.addAll(getBeanProperties());
            }
        }
        return elements.stream()
            .filter(element -> matches(result, element))
            .map(elementType::cast)
            .toList();
    }

    /**
     * Walks the hierarchy with {@code getDeclaredMethods()} rather than using
     * {@code getMethods()}.
     *
     * <p>{@code getMethods()} returns only *public* members, so protected, package-private
     * and declared private methods were invisible on a classpath type, and it includes
     * {@code java.lang.Object}'s methods, which the source path never produces -- so the two
     * element kinds disagreed about what a class declares.
     */
    private <T extends Element> void collectMethods(ElementQuery.Result<T> result, List<Element> elements) {
        Set<MethodKey> seen = new HashSet<>();
        for (Class<?> current : hierarchy(result.isOnlyDeclared())) {
            ClassElement declaring = declaringElement(current);
            for (Method method : current.getDeclaredMethods()) {
                if (method.isSynthetic() || !seen.add(new MethodKey(method.getName(), List.of(method.getParameterTypes())))) {
                    continue;
                }
                elements.add(methodElement(method, declaring));
            }
        }
    }

    private <T extends Element> void collectFields(ElementQuery.Result<T> result, List<Element> elements) {
        Set<String> seen = new HashSet<>();
        for (Class<?> current : hierarchy(result.isOnlyDeclared())) {
            ClassElement declaring = declaringElement(current);
            for (Field field : current.getDeclaredFields()) {
                // A field hides rather than overloads, so a name is enough to de-duplicate.
                if (field.isSynthetic() || !seen.add(field.getName())) {
                    continue;
                }
                elements.add(fieldElement(field, declaring));
            }
        }
    }

    /**
     * The classes to take declared members from, nearest first. {@code java.lang.Object} is
     * excluded: its members are not part of what a class declares, and the source path never
     * reports them.
     */
    private List<Class<?>> hierarchy(boolean onlyDeclared) {
        if (onlyDeclared) {
            return List.of(componentType);
        }
        List<Class<?>> hierarchy = new ArrayList<>();
        Set<Class<?>> visited = new HashSet<>();
        collectHierarchy(componentType, hierarchy, visited);
        return hierarchy;
    }

    private static void collectHierarchy(@Nullable Class<?> type, List<Class<?>> hierarchy, Set<Class<?>> visited) {
        if (type == null || type == Object.class || !visited.add(type)) {
            return;
        }
        hierarchy.add(type);
        collectHierarchy(type.getSuperclass(), hierarchy, visited);
        for (Class<?> interfaceType : type.getInterfaces()) {
            collectHierarchy(interfaceType, hierarchy, visited);
        }
    }

    private ClassElement declaringElement(Class<?> declaring) {
        return declaring == componentType ? this : new ScalaLoadedClassElement(declaring, visitorContext);
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

    /**
     * The identity of a classpath element is the type it names, exactly as for a source
     * element.
     *
     * <p>Without this a classpath element keyed on its {@code Class} object and a source
     * element keyed on {@code (name, arrayDimensions)} were never equal, so the same type
     * reached by two routes -- {@code getClassElement(name)} and the supertype of a class in
     * the compilation -- was two elements with different hash codes. Micronaut caches
     * elements by identity, so whichever route was taken first decided what the other route
     * saw.</p>
     */
    @Override
    protected Class<?> equalityType() {
        return ScalaClassElement.class;
    }

    @Override
    protected Object equalityKey() {
        return new ScalaClassElement.ClassElementKey(getName(), getArrayDimensions());
    }

    @Override
    public ClassElement withTypeArguments(Map<String, ClassElement> typeArguments) {
        return new ScalaLoadedClassElement(type, visitorContext, getAnnotationMetadata(), typeArguments);
    }

    @Override
    public ClassElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
        return new ScalaLoadedClassElement(type, visitorContext, annotationMetadata, typeArguments);
    }

    private LoadedMethodElement methodElement(Method method, ClassElement declaringType) {
        return new LoadedMethodElement(this, declaringType, method, parameters(method), visitorContext, loadedMetadata(method.getName(), method, ClasspathAnnotationMetadataReader.methodAnnotations(method), visitorContext));
    }

    private LoadedConstructorElement constructorElement(Constructor<?> constructor) {
        return constructorElement(constructor, this);
    }

    private LoadedConstructorElement constructorElement(Constructor<?> constructor, ClassElement declaringType) {
        return new LoadedConstructorElement(this, declaringType, constructor, parameters(constructor), visitorContext, loadedMetadata("<init>", constructor, ClasspathAnnotationMetadataReader.constructorAnnotations(constructor), visitorContext));
    }

    private LoadedFieldElement fieldElement(Field field, ClassElement declaringType) {
        return new LoadedFieldElement(
            this,
            declaringType,
            field,
            classElement(field.getType(), visitorContext),
            classElement(field.getGenericType(), field.getType(), visitorContext),
            visitorContext,
            loadedMetadata(field.getName(), field, ClasspathAnnotationMetadataReader.fieldAnnotations(field), visitorContext)
        );
    }

    private ParameterElement[] parameters(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        Type[] genericParameterTypes = executable.getGenericParameterTypes();
        Class<?>[] parameterTypes = executable.getParameterTypes();
        ParameterElement[] parameterElements = new ParameterElement[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            // Only the generic type is substituted. The erasure is the JVM signature, and a
            // generated adapter or proxy overrides the method by it: substituting there made
            // an @Adapter implement `onApplicationEvent(StartupEvent)` and leave the
            // interface's own `onApplicationEvent(Object)` abstract.
            ClassElement type = classElement(parameterTypes[i], visitorContext);
            ClassElement genericType = substituteDeep(classElement(genericParameterTypes[i], parameterTypes[i], visitorContext), 0);
            parameterElements[i] = new LoadedParameterElement(
                type,
                genericType,
                parameters[i],
                parameters[i].getName(),
                visitorContext,
                loadedMetadata(parameters[i].getName(), executable, ClasspathAnnotationMetadataReader.parameterAnnotations(executable, i), visitorContext)
            );
        }
        return parameterElements;
    }

    /**
     * Builds a classpath member's metadata through the same builder the source elements use,
     * so stereotypes, aliases, mappers and repeatable containers are resolved for it.
     */
    private static AnnotationMetadata loadedMetadata(
        String name,
        Object nativeType,
        List<AnnotationValue<?>> annotations,
        ScalaVisitorContext visitorContext) {
        if (annotations.isEmpty()) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        return visitorContext.annotationMetadata(
            LoadedAnnotatedElementData.of(name, nativeType, annotations, visitorContext)
        );
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

    private static ClassElement classElement(Type genericType, Class<?> erasedType, @Nullable ScalaVisitorContext visitorContext) {
        try {
            if (genericType instanceof Class<?> genericClass) {
                return classElement(genericClass, visitorContext);
            }
            if (visitorContext != null) {
                if (genericType instanceof ParameterizedType parameterizedType) {
                    return classElement(erasedType, visitorContext)
                        .withTypeArguments(typeArguments(erasedType, parameterizedType, visitorContext));
                }
                if (genericType instanceof TypeVariable<?> typeVariable) {
                    return placeholderElement(typeVariable, erasedType, visitorContext);
                }
                if (genericType instanceof WildcardType wildcardType) {
                    return wildcardElement(wildcardType, erasedType, visitorContext);
                }
            }
            // Deliberately not `ClassElement.of(genericType)`: Core's reflective factory
            // hands back its own element kinds -- `ReflectGenericPlaceholderElement` and
            // friends -- which are immutable, so a visitor annotating one fails with "does
            // not support adding annotations at compilation time", and which fail the
            // `instanceof` checks the generics writers make against this repository's
            // element kinds.
            return classElement(erasedType, visitorContext);
        } catch (RuntimeException ignored) {
            return classElement(erasedType, visitorContext);
        }
    }

    /**
     * A type variable of a classpath type, as this repository's own placeholder element.
     */
    private static ClassElement placeholderElement(
        TypeVariable<?> typeVariable,
        Class<?> erasedType,
        ScalaVisitorContext visitorContext) {
        // A bound that is itself a type variable is kept as one. `<S extends E> S save(S)`
        // declares S in terms of the class's E, so erasing the bound -- E erases to Object --
        // throws away the only link between the method's variable and what the class bound.
        List<ScalaTypeData> bounds = Arrays.stream(typeVariable.getBounds())
            .map(ScalaLoadedClassElement::boundTypeData)
            .toList();
        // A type variable erases to its bound, and the bound is normally parameterized by the
        // variable itself -- `E extends Enum<E>`. Modelling that literally does not terminate,
        // so the second level collapses: core's Java module, on meeting a type it is already
        // inside, resolves every parameter to Object rather than to nothing. Reporting no
        // arguments at all was a level short of that, and left a caller unable to tell a
        // parameterized bound from a raw one.
        Map<String, ScalaTypeData> collapsedArguments = new LinkedHashMap<>();
        for (TypeVariable<?> parameter : erasedType.getTypeParameters()) {
            collapsedArguments.put(parameter.getName(), plainTypeData(Object.class));
        }
        return visitorContext.getElementFactory().newClassElement(new ScalaTypeData(
            erasedType.getName(),
            false,
            0,
            erasedType.isInterface(),
            collapsedArguments,
            null,
            List.of(),
            List.of(),
            false,
            typeVariable,
            true,
            typeVariable.getName(),
            bounds
        ));
    }

    /**
     * A bound, as a variable when it is one and as its erasure otherwise.
     *
     * @param bound The declared bound
     * @return The type data
     */
    private static ScalaTypeData boundTypeData(Type bound) {
        return bound instanceof TypeVariable<?> variable
            ? variableTypeData(variable)
            : plainTypeData(erasedClass(bound, Object.class));
    }

    /**
     * A type variable named as a variable rather than reduced to its erasure, for use as the
     * bound of another variable. The name is what matters, since that is what a parameterized
     * supertype binds, but it keeps its erasure as its own bound: the generics writer reads the
     * first bound of every placeholder it is given, and a placeholder with none fails there
     * rather than anywhere near here.
     *
     * @param typeVariable The variable
     * @return The type data
     */
    private static ScalaTypeData variableTypeData(TypeVariable<?> typeVariable) {
        Class<?> erasure = erasedClass(typeVariable, Object.class);
        return new ScalaTypeData(
            erasure.getName(), false, 0, erasure.isInterface(), Map.of(), null, List.of(), List.of(),
            false, typeVariable.getName(), true, typeVariable.getName(),
            List.of(plainTypeData(erasure)), false, List.of(), List.of()
        );
    }

    /**
     * A wildcard of a classpath type, as this repository's own wildcard element.
     */
    private static ClassElement wildcardElement(
        WildcardType wildcardType,
        Class<?> erasedType,
        ScalaVisitorContext visitorContext) {
        // Kept as variables for the same reason a type variable's bound is: `? extends E` is
        // how `deleteAll(Iterable<? extends E>)` is declared, and erasing E to Object leaves
        // nothing to bind, so Micronaut Data read the parameter as a property name instead.
        List<ScalaTypeData> upperBounds = Arrays.stream(wildcardType.getUpperBounds())
            .map(ScalaLoadedClassElement::boundTypeData)
            .toList();
        List<ScalaTypeData> lowerBounds = Arrays.stream(wildcardType.getLowerBounds())
            .map(ScalaLoadedClassElement::boundTypeData)
            .toList();
        return visitorContext.getElementFactory().newClassElement(new ScalaTypeData(
            erasedType.getName(),
            false,
            0,
            erasedType.isInterface(),
            Map.of(),
            null,
            List.of(),
            List.of(),
            false,
            wildcardType,
            false,
            null,
            List.of(),
            true,
            upperBounds,
            lowerBounds
        ));
    }

    private static ScalaTypeData plainTypeData(Class<?> type) {
        return new ScalaTypeData(type.getName(), type.isPrimitive(), 0, type.isInterface(), Map.of());
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

    private record MethodKey(String name, List<Class<?>> parameterTypes) {
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

        /**
         * Core's default returns an anonymous delegate that overrides only the read side, so
         * every write through it fails with "Element of type [MethodElement$1] does not
         * support adding annotations at compilation time". A property annotates through this
         * delegate, and so does any visitor reaching a method this way, so a classpath method
         * could not be annotated at all. This element's own metadata is mutable.
         */
        @Override
        public MutableAnnotationMetadataDelegate<AnnotationMetadata> getMethodAnnotationMetadata() {
            return getElementAnnotationMetadata();
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
            this(owningType, declaringType, method, parameters, visitorContext, annotationMetadata, true);
        }

        /**
         * @param sharedMetadata Whether this element reports the one mutable metadata instance
         *     kept for the native method. True for every element that stands for the method
         *     itself, so a visitor's annotation is still there the next time the declaring type
         *     is asked for its members. False when a caller is deliberately supplying different
         *     metadata -- a bean builder generating several beans from one method gives each its
         *     own qualifier, and sharing would make them one bean declared twice.
         */
        private LoadedMethodElement(
            ClassElement owningType,
            ClassElement declaringType,
            Method method,
            ParameterElement[] parameters,
            ScalaVisitorContext visitorContext,
            AnnotationMetadata annotationMetadata,
            boolean sharedMetadata) {
            super(
                method.getName(),
                method,
                javaModifiers(method.getModifiers()),
                sharedMetadata
                    ? visitorContext.loadedAnnotationMetadata(method, () -> annotationMetadata)
                    : MutableAnnotationMetadata.of(annotationMetadata),
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
            ClassElement returnType = classElement(method.getGenericReturnType(), method.getReturnType(), visitorContext);
            // Either end of the method can know what the variables are bound to. A method
            // collected into the class that inherits it is re-parented to that class, which
            // binds nothing, while the declaring type remains the parameterized classpath
            // element it was read from.
            ScalaLoadedClassElement source = owningType instanceof ScalaLoadedClassElement owner
                ? owner
                : declaringType instanceof ScalaLoadedClassElement declaring ? declaring : null;
            return source == null ? returnType : source.substituteDeep(returnType, 0);
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
            // The method's own annotations, not the composed view. Re-parenting changes which
            // type sits above the method, and passing the composed metadata would fold the old
            // owner's annotations into the new element's own -- which is the surface an override
            // supplies and a visitor writes to.
            return new LoadedMethodElement(
                owningType, declaringType, method, parameters, visitorContext,
                getMethodAnnotationMetadata().getAnnotationMetadata());
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

        /**
         * Core's default returns an anonymous delegate that overrides only the read side, so
         * every write through it fails with "Element of type [MethodElement$1] does not
         * support adding annotations at compilation time". A property annotates through this
         * delegate, and so does any visitor reaching a method this way, so a classpath method
         * could not be annotated at all. This element's own metadata is mutable.
         */
        @Override
        public MutableAnnotationMetadataDelegate<AnnotationMetadata> getMethodAnnotationMetadata() {
            return getElementAnnotationMetadata();
        }

        /**
         * The method's own annotations beneath those of the type that owns it, which is what
         * {@link MethodElementAnnotationMetadata} defines a method element to report and what
         * the source method element already did.
         *
         * <p>A method read from the classpath reported only what it declared. That is the same
         * answer for a method used where it was declared and the wrong one for a method
         * inherited into a class that annotates it: a repository's `@Repository` sits on the
         * Scala type, and the executable methods generated for what it inherits from
         * {@code CrudRepository} carried no interceptor binding, so the introduction proxy had
         * an interceptor for the methods the repository declared and none for the rest.</p>
         *
         * <p>Only the read view composes. Writing still goes to the method's own metadata, and
         * {@code getMethodAnnotationMetadata()} still answers with it alone -- the proxy writer
         * asks that question to decide whether a method itself declares advice, and answering it
         * with the type's annotations makes every method of an advised type look individually
         * advised.</p>
         */
        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            // Built per call rather than cached: the hierarchy resolves its merged view once,
            // and a visitor that annotates the method afterwards -- which is when Micronaut Data
            // records the query it derived -- would not appear in a view built before it ran.
            return new MethodElementAnnotationMetadata(this).getAnnotationMetadata();
        }

        @Override
        public MethodElement withAnnotationMetadata(AnnotationMetadata annotationMetadata) {
            return new LoadedMethodElement(owningType, declaringType, method, parameters, visitorContext, annotationMetadata, false);
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

        /**
         * Reads and writes have to reach the same metadata. Only the read side was
         * overridden, so {@code annotate(...)} went to the element's own metadata while
         * every read came from the property's -- the annotation was stored and then never
         * seen again.
         */
        @Override
        protected MutableAnnotationMetadataDelegate<?> getAnnotationMetadataToWrite() {
            return annotationMetadata;
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
