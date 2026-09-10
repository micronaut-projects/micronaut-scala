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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds Micronaut annotation metadata from the reduced Scala compiler model.
 */
public final class ScalaAnnotationMetadataBuilder extends AbstractAnnotationMetadataBuilder<Object, ScalaAnnotationData> {

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

    private final VisitorContext visitorContext;
    private final Map<String, ScalaAnnotationTypeData> nativeAnnotationTypes = new LinkedHashMap<>();
    /** Names already looked up and not resolvable, so the compiler is asked only once each. */
    private final Set<String> unresolvableAnnotationTypes = new HashSet<>();

    public ScalaAnnotationMetadataBuilder(VisitorContext visitorContext) {
        this.visitorContext = visitorContext;
    }

    /**
     * The raw documentation comment on a native element.
     *
     * <p>Reached through the builder because that is what every element is handed; the
     * documentation itself has nothing to do with annotation metadata.</p>
     *
     * @param nativeType The native compiler object
     * @return The raw comment, if the compiler kept one for it
     */
    java.util.Optional<String> documentation(Object nativeType) {
        if (visitorContext instanceof ScalaVisitorContext scalaVisitorContext) {
            return scalaVisitorContext.documentation(nativeType);
        }
        return java.util.Optional.empty();
    }

    /**
     * Build metadata for a Scala model element.
     *
     * @param element The element
     * @return The annotation metadata
     */
    public MutableAnnotationMetadata buildMetadata(ScalaAnnotatedElementData element) {
        registerAnnotationTypes(element.annotations());
        MutableAnnotationMetadata annotationMetadata = MutableAnnotationMetadata.of(buildInternal(element));
        if (element instanceof ScalaClassData classData && isAnnotationType(classData)) {
            addJavaMetaAnnotations(annotationMetadata, classData);
        }
        return annotationMetadata;
    }

    @Override
    protected Object getTypeForAnnotation(ScalaAnnotationData annotationMirror) {
        return annotationType(annotationMirror);
    }

    @Override
    protected boolean hasAnnotation(Object element, Class<? extends Annotation> annotation) {
        return hasAnnotation(element, annotation.getName());
    }

    @Override
    protected boolean hasAnnotation(Object element, String annotation) {
        return getAnnotationsForType(element).stream()
            .anyMatch(annotationData -> annotationData.name().equals(annotation));
    }

    @Override
    protected boolean hasAnnotations(Object element) {
        return !getAnnotationsForType(element).isEmpty();
    }

    @Override
    protected String getAnnotationTypeName(ScalaAnnotationData annotationMirror) {
        return annotationMirror.name();
    }

    @Override
    protected String getElementName(Object element) {
        if (element instanceof ScalaAnnotatedElementData annotated) {
            return annotated.name();
        }
        if (element instanceof AnnotationTypeElement annotationType) {
            return annotationType.name();
        }
        if (element instanceof AnnotationMemberElement memberElement) {
            return memberElement.name();
        }
        if (element instanceof UnresolvedAnnotationMember unresolvedAnnotationMember) {
            return unresolvedAnnotationMember.name();
        }
        return String.valueOf(element);
    }

    @Override
    protected List<? extends ScalaAnnotationData> getAnnotationsForType(Object element) {
        if (element instanceof ScalaAnnotatedElementData annotated) {
            return annotated.annotations();
        }
        if (element instanceof AnnotationTypeElement annotationType) {
            ScalaAnnotationTypeData nativeType = annotationType.nativeType();
            return nativeType == null ? Collections.emptyList() : nativeType.annotations();
        }
        if (element instanceof AnnotationMemberElement memberElement) {
            ScalaAnnotationMemberData nativeMember = memberElement.nativeMember();
            return nativeMember == null ? Collections.emptyList() : nativeMember.annotations();
        }
        return Collections.emptyList();
    }

    @Override
    protected List<Object> buildHierarchy(Object element, boolean inheritTypeAnnotations, boolean declaredOnly) {
        if (element == null) {
            return new ArrayList<>();
        }
        if (declaredOnly) {
            return new ArrayList<>(List.of(element));
        }
        if (element instanceof ScalaClassData classData) {
            List<Object> hierarchy = new ArrayList<>();
            populateClassHierarchy(classData, hierarchy, new HashSet<>());
            return hierarchy;
        }
        if (element instanceof ScalaParameterData parameterData && !parameterData.overriddenParameters().isEmpty()) {
            // The same rule as a method, by position: core takes the parameter at each index from
            // every overridden method. An `@Inherited` annotation written once on a trait member's
            // parameter then applies to every implementation of it.
            List<Object> hierarchy = new ArrayList<>(parameterData.overriddenParameters());
            hierarchy.add(element);
            return hierarchy;
        }
        if (element instanceof ScalaMethodData methodData && !methodData.overriddenMethods().isEmpty()) {
            // The declarations this method overrides come first, so their annotations arrive as
            // inherited rather than declared -- the order core's Java module builds for an
            // ExecutableElement. Without them the hierarchy for a method was the method alone,
            // and an annotation written on an abstract trait member reached no implementation.
            List<Object> hierarchy = new ArrayList<>(methodData.overriddenMethods());
            hierarchy.add(element);
            return hierarchy;
        }
        return new ArrayList<>(List.of(element));
    }

    @Override
    protected void readAnnotationRawValues(
        Object originatingElement,
        String annotationName,
        Object member,
        String memberName,
        Object annotationValue,
        Map<CharSequence, Object> annotationValues) {
        readAnnotationRawValues(originatingElement, annotationName, member, memberName, annotationValue, annotationValues, new LinkedHashMap<>());
    }

    @Override
    protected void readAnnotationRawValues(
        Object originatingElement,
        String annotationName,
        Object member,
        String memberName,
        Object annotationValue,
        Map<CharSequence, Object> annotationValues,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        if (memberName != null && annotationValue != null && !containsAnnotationValue(annotationValues, memberName)) {
            Object resolvedValue = normalizeValue(originatingElement, member, annotationValue, resolvedDefaults);
            if (resolvedValue != null) {
                if (isEvaluatedExpression(resolvedValue)) {
                    resolvedValue = buildEvaluatedExpressionReference(originatingElement, annotationName, memberName, resolvedValue);
                }
                validateAnnotationValue(originatingElement, annotationName, member, memberName, resolvedValue);
                annotationValues.put(memberName, resolvedValue);
            }
        }
    }

    @Override
    protected boolean isValidationRequired(Object member) {
        return isValidationRequired(member, new ArrayList<>());
    }

    @Override
    protected void addError(Object originatingElement, String error) {
        visitorContext.fail(error, null);
    }

    @Override
    protected void addWarning(Object originatingElement, String warning) {
        visitorContext.warn(warning, null);
    }

    @Override
    protected Object readAnnotationValue(Object originatingElement, Object member, String annotationName, String memberName, Object annotationValue) {
        return normalizeValue(originatingElement, member, annotationValue, new LinkedHashMap<>());
    }

    @Override
    protected Map<? extends Object, ?> readAnnotationDefaultValues(String annotationName, Object annotationType) {
        AnnotationTypeElement typeElement = annotationType(annotationName, annotationType);
        Map<Object, Object> values = new LinkedHashMap<>();
        ScalaAnnotationTypeData nativeType = typeElement.nativeType();
        if (nativeType != null) {
            for (ScalaAnnotationMemberData member : nativeType.members().values()) {
                Object defaultValue = member.defaultValue();
                if (isValidDefaultValue(defaultValue)) {
                    values.put(new AnnotationMemberElement(typeElement, member), defaultValue);
                }
            }
        }
        return values;
    }

    @Override
    protected Map<? extends Object, ?> readAnnotationRawValues(ScalaAnnotationData annotationMirror) {
        if (annotationMirror.values().isEmpty()) {
            return Map.of();
        }
        Object annotationType = getTypeForAnnotation(annotationMirror);
        Map<Object, Object> values = new LinkedHashMap<>(annotationMirror.values().size());
        for (Map.Entry<CharSequence, Object> entry : annotationMirror.values().entrySet()) {
            Object member = getAnnotationMember(annotationType, entry.getKey());
            values.put(member == null ? new UnresolvedAnnotationMember(entry.getKey().toString()) : member, entry.getValue());
        }
        return values;
    }

    @Override
    protected <K extends Annotation> Optional<AnnotationValue<K>> getAnnotationValues(Object originatingElement, Object member, Class<K> annotationType) {
        if (member instanceof AnnotationMemberElement memberElement) {
            ScalaAnnotationMemberData nativeMember = memberElement.nativeMember();
            if (nativeMember != null) {
                for (ScalaAnnotationData annotation : nativeMember.annotations()) {
                    if (annotation.name().equals(annotationType.getName())) {
                        return Optional.of(AnnotationValue.builder(annotationType)
                            .members(annotationValues(originatingElement, annotation))
                            .build());
                    }
                }
                return repeatableContainer(originatingElement, nativeMember, annotationType);
            }
        }
        return Optional.empty();
    }

    /**
     * The container a repeatable annotation would have been collapsed into, synthesised from the
     * repeats themselves.
     *
     * <p>Java has no repeats in the model: javac collapses two {@code @AliasFor} into one
     * {@code @Aliases} before a processor sees them, and core relies on that -- it asks for
     * {@code Aliases} first and only falls back to a single {@code AliasFor}. Scala has no notion
     * of a repeatable container at all, so both annotations stay separate and core saw only the
     * first: a member carrying two {@code @AliasFor} had exactly one of its aliases applied, and
     * which one depended on declaration order.</p>
     *
     * @return the container, or empty when the requested type is not the container of an
     *     annotation this member repeats
     */
    private <K extends Annotation> Optional<AnnotationValue<K>> repeatableContainer(
        Object originatingElement,
        ScalaAnnotationMemberData nativeMember,
        Class<K> annotationType) {
        List<AnnotationValue<?>> repeats = new ArrayList<>();
        for (ScalaAnnotationData annotation : nativeMember.annotations()) {
            ScalaAnnotationTypeData annotationTypeData = nativeAnnotationType(annotation.name());
            if (annotationTypeData == null
                || !annotationType.getName().equals(annotationTypeData.repeatableContainerName())) {
                continue;
            }
            repeats.add(AnnotationValue.builder(annotation.name())
                .members(annotationValues(originatingElement, annotation))
                .build());
        }
        // One occurrence is not a container in Java either -- javac leaves it as the annotation
        // itself, and core's fallback reads it directly.
        if (repeats.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(AnnotationValue.builder(annotationType)
            .member(AnnotationMetadata.VALUE_MEMBER, repeats.toArray(AnnotationValue[]::new))
            .build());
    }

    @Override
    protected String getAnnotationMemberName(Object member) {
        if (member instanceof AnnotationMemberElement memberElement) {
            return memberElement.name();
        }
        if (member instanceof UnresolvedAnnotationMember unresolvedAnnotationMember) {
            return unresolvedAnnotationMember.name();
        }
        return String.valueOf(member);
    }

    @Override
    protected @Nullable String getRepeatableName(ScalaAnnotationData annotationMirror) {
        return repeatableContainerName(annotationType(annotationMirror));
    }

    @Override
    protected @Nullable String getRepeatableContainerNameForType(Object annotationType) {
        return repeatableContainerName(annotationType(annotationTypeName(annotationType), annotationType));
    }

    @Override
    protected Optional<Object> getAnnotationMirror(String annotationName) {
        return Optional.ofNullable(nativeAnnotationType(annotationName))
            .map(nativeType -> new AnnotationTypeElement(annotationName, nativeType));
    }

    @Override
    protected @Nullable String getOriginatingClassName(Object originatingElement) {
        if (originatingElement instanceof ScalaClassData classData) {
            return classData.name();
        }
        if (originatingElement instanceof ScalaAnnotatedElementData annotatedElementData
            && visitorContext instanceof ScalaVisitorContext scalaVisitorContext) {
            return scalaVisitorContext.originatingClassName(annotatedElementData).orElse(null);
        }
        return null;
    }

    @Override
    protected @Nullable Object getAnnotationMember(Object annotationElement, CharSequence member) {
        AnnotationTypeElement typeElement = annotationType(annotationTypeName(annotationElement), annotationElement);
        ScalaAnnotationTypeData nativeType = typeElement.nativeType();
        if (nativeType != null) {
            ScalaAnnotationMemberData nativeMember = nativeType.members().get(member.toString());
            if (nativeMember != null) {
                return new AnnotationMemberElement(typeElement, nativeMember);
            }
        }
        return null;
    }

    @Override
    protected VisitorContext getVisitorContext() {
        return visitorContext;
    }

    @Override
    protected RetentionPolicy getRetentionPolicy(Object annotation) {
        AnnotationTypeElement annotationType = annotationType(annotationTypeName(annotation), annotation);
        ScalaAnnotationTypeData nativeType = annotationType.nativeType();
        if (nativeType == null) {
            return RetentionPolicy.RUNTIME;
        }
        if (nativeType.retentionPolicyName() != null) {
            try {
                return RetentionPolicy.valueOf(nativeType.retentionPolicyName());
            } catch (IllegalArgumentException e) {
                // A retention that does not name a policy must not throw out of the compiler.
                return defaultRetentionPolicy(nativeType);
            }
        }
        return defaultRetentionPolicy(nativeType);
    }

    /**
     * The retention of an annotation type that declares none.
     *
     * <p>For a Java annotation the JLS says {@code CLASS}. A Scala annotation -- a class
     * extending {@code StaticAnnotation} -- is not a Java annotation type and has no JLS
     * retention at all; it exists purely as compile-time metadata that Micronaut bakes into the
     * generated bean definition, so treating it as {@code CLASS} would drop it. A
     * {@code @Qualifier} declared that way simply stopped being a qualifier.
     */
    private static RetentionPolicy defaultRetentionPolicy(ScalaAnnotationTypeData nativeType) {
        return nativeType.javaDefined() ? RetentionPolicy.CLASS : RetentionPolicy.RUNTIME;
    }

    @Override
    protected boolean isExcludedAnnotation(Object element, String annotationName) {
        if (annotationName.startsWith("java.lang.annotation.")
            && (element instanceof AnnotationTypeElement
            || (element instanceof ScalaClassData classData && isAnnotationType(classData)))) {
            return false;
        }
        return super.isExcludedAnnotation(element, annotationName);
    }

    private void addJavaMetaAnnotations(MutableAnnotationMetadata annotationMetadata, ScalaClassData classData) {
        for (ScalaAnnotationData annotation : classData.annotations()) {
            if (annotation.name().startsWith("java.lang.annotation.")) {
                annotationMetadata.addDeclaredAnnotation(
                    annotation.name(),
                    annotationValues(classData, annotation),
                    getRetentionPolicy(annotationType(annotation))
                );
            }
        }
    }

    private Map<CharSequence, Object> annotationValues(Object originatingElement, ScalaAnnotationData annotation) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Map.Entry<? extends Object, ?> entry : readAnnotationRawValues(annotation).entrySet()) {
            Object member = entry.getKey();
            readAnnotationRawValues(
                originatingElement,
                annotation.name(),
                member,
                getAnnotationMemberName(member),
                entry.getValue(),
                values);
        }
        return values;
    }

    private boolean isAnnotationType(ScalaClassData classData) {
        return classData.annotationType()
            || isAnnotationType(classData.superType())
            || classData.interfaces().stream().anyMatch(this::isAnnotationType);
    }

    private boolean isAnnotationType(@Nullable ScalaTypeData typeData) {
        if (typeData == null) {
            return false;
        }
        return "scala.annotation.Annotation".equals(typeData.name())
            || "scala.annotation.StaticAnnotation".equals(typeData.name())
            || isAnnotationType(typeData.superType())
            || typeData.interfaces().stream().anyMatch(this::isAnnotationType);
    }

    private boolean isValidationRequired(Object member, List<String> visited) {
        for (ScalaAnnotationData annotation : getAnnotationsForType(member)) {
            String annotationName = annotation.name();
            if (annotationName.startsWith("jakarta.validation")) {
                return true;
            }
            if (!visited.contains(annotationName)) {
                visited.add(annotationName);
                if (isValidationRequired(getTypeForAnnotation(annotation), visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Object normalizeValue(
        Object originatingElement,
        Object member,
        Object value,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        if (member instanceof AnnotationMemberElement memberElement && memberElement.nativeMember() != null) {
            return normalizeNativeValue(originatingElement, memberElement.nativeMember(), value, resolvedDefaults);
        }
        Object resolvedValue = normalizeLooseValue(originatingElement, value, resolvedDefaults);
        return resolvedValue == null ? value : resolvedValue;
    }

    private Object normalizeNativeValue(
        Object originatingElement,
        ScalaAnnotationMemberData nativeMember,
        Object value,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        if (nativeMember.array()) {
            List<Object> values = arrayValues(value);
            if (nativeMember.classType()) {
                AnnotationClassValue<?>[] converted = new AnnotationClassValue<?>[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    converted[i] = annotationClassValue(values.get(i));
                }
                return converted;
            }
            if (nativeMember.enumType()) {
                String[] converted = new String[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    converted[i] = enumValue(values.get(i));
                }
                return converted;
            }
            if (nativeMember.annotationType()) {
                AnnotationValue<?>[] converted = new AnnotationValue<?>[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    converted[i] = nestedAnnotationValue(originatingElement, nativeMember.typeName(), values.get(i), resolvedDefaults);
                }
                return converted;
            }
            return value;
        }
        if (nativeMember.classType()) {
            return annotationClassValue(value);
        }
        if (nativeMember.enumType()) {
            return enumValue(value);
        }
        if (nativeMember.annotationType()) {
            return nestedAnnotationValue(originatingElement, nativeMember.typeName(), value, resolvedDefaults);
        }
        return value;
    }

    @Nullable
    private Object normalizeLooseValue(
        Object originatingElement,
        @Nullable Object value,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        if (value == null) {
            return null;
        }
        if (value instanceof Class<?> || value instanceof AnnotationClassValue<?> || value instanceof ScalaClassValueData) {
            return annotationClassValue(value);
        }
        if (value instanceof Class<?>[] types) {
            return annotationClassValues(types);
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof AnnotationValue<?> annotationValue) {
            return nestedAnnotationValue(originatingElement, annotationValue, resolvedDefaults);
        }
        if (value instanceof AnnotationValue<?>[] annotationValues) {
            AnnotationValue<?>[] values = new AnnotationValue<?>[annotationValues.length];
            for (int i = 0; i < annotationValues.length; i++) {
                values[i] = nestedAnnotationValue(originatingElement, annotationValues[i], resolvedDefaults);
            }
            return values;
        }
        return value;
    }

    private List<Object> arrayValues(Object value) {
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                values.add(Array.get(value, i));
            }
            return values;
        }
        return List.of(value);
    }

    private AnnotationClassValue<?> annotationClassValue(Object value) {
        if (value instanceof AnnotationClassValue<?> annotationClassValue) {
            return annotationClassValue;
        }
        if (value instanceof ScalaClassValueData classValueData) {
            registerAnnotationType(classValueData.annotationType());
            return new AnnotationClassValue<>(classValueData.name());
        }
        if (value instanceof Class<?> type) {
            return new AnnotationClassValue<>(type);
        }
        return new AnnotationClassValue<>(String.valueOf(value));
    }

    private AnnotationClassValue<?>[] annotationClassValues(Class<?>[] types) {
        AnnotationClassValue<?>[] values = new AnnotationClassValue<?>[types.length];
        for (int i = 0; i < types.length; i++) {
            values[i] = new AnnotationClassValue<>(types[i]);
        }
        return values;
    }

    private String enumValue(Object value) {
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return String.valueOf(value);
    }

    private AnnotationValue<?> nestedAnnotationValue(
        Object originatingElement,
        String expectedTypeName,
        Object value,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        if (value instanceof AnnotationValue<?> annotationValue) {
            return nestedAnnotationValue(originatingElement, annotationValue, resolvedDefaults);
        }
        if (value instanceof ScalaAnnotationData annotationData) {
            return readNestedAnnotationValue(originatingElement, annotationData, resolvedDefaults);
        }
        return AnnotationValue.builder(expectedTypeName).build();
    }

    private AnnotationValue<?> nestedAnnotationValue(
        Object originatingElement,
        AnnotationValue<?> annotationValue,
        Map<String, Map<CharSequence, Object>> resolvedDefaults) {
        return readNestedAnnotationValue(
            originatingElement,
            new ScalaAnnotationData(annotationValue.getAnnotationName(), annotationValue.getValues()),
            resolvedDefaults);
    }

    private boolean containsAnnotationValue(Map<CharSequence, Object> annotationValues, String memberName) {
        for (CharSequence key : annotationValues.keySet()) {
            if (memberName.contentEquals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An empty string is a real default, not the absence of one. Treating it as absent
     * dropped the declared default of some of the most common annotations there are --
     * {@code @Named}, {@code @Property} and {@code @Requires.property} all default to it --
     * so Micronaut could not tell that an explicitly empty value matched the default.
     */
    private boolean isValidDefaultValue(@Nullable Object defaultValue) {
        return defaultValue != null;
    }

    private AnnotationTypeElement annotationType(ScalaAnnotationData annotation) {
        ScalaAnnotationTypeData nativeType = annotation.annotationType();
        if (nativeType != null) {
            registerAnnotationType(nativeType);
            // Read back rather than using the argument: registration may replace the type with
            // a completed copy, and the one carried on the annotation is the incomplete one.
            nativeType = nativeAnnotationTypes.getOrDefault(annotation.name(), nativeType);
        } else {
            nativeType = nativeAnnotationType(annotation.name());
        }
        return new AnnotationTypeElement(annotation.name(), nativeType);
    }

    private AnnotationTypeElement annotationType(String annotationName) {
        return new AnnotationTypeElement(annotationName, nativeAnnotationType(annotationName));
    }

    /**
     * The annotation type for a name, resolving it through the compiler if it has not been
     * seen on an extracted element.
     *
     * <p>{@code nativeAnnotationTypes} is populated as elements are visited, so an annotation
     * added programmatically -- by {@code element.annotate(...)}, a mapper or a
     * bean-definition builder -- had no mirror unless something else in the compilation
     * happened to use it first. Its meta-annotations were then silently not processed, so
     * whether an added annotation carried its stereotypes depended on compilation order.
     */
    private @Nullable ScalaAnnotationTypeData nativeAnnotationType(String annotationName) {
        ScalaAnnotationTypeData nativeType = nativeAnnotationTypes.get(annotationName);
        if (nativeType != null || !unresolvableAnnotationTypes.add(annotationName)) {
            return nativeType;
        }
        if (!(visitorContext instanceof ScalaVisitorContext scalaVisitorContext)) {
            return null;
        }
        ScalaAnnotationTypeData resolved = scalaVisitorContext.resolveAnnotationType(annotationName);
        if (resolved == null) {
            return null;
        }
        // Registering pulls in the type's own meta-annotations and members, which is what
        // makes stereotype resolution work for it.
        registerAnnotationType(resolved);
        unresolvableAnnotationTypes.remove(annotationName);
        return nativeAnnotationTypes.get(annotationName);
    }

    private AnnotationTypeElement annotationType(String annotationName, @Nullable Object annotationType) {
        if (annotationType instanceof AnnotationTypeElement annotationTypeElement) {
            return annotationTypeElement;
        }
        return annotationType(annotationName);
    }

    private void registerAnnotationTypes(List<ScalaAnnotationData> annotations) {
        for (ScalaAnnotationData annotation : annotations) {
            registerAnnotationData(annotation);
        }
    }

    private void registerAnnotationData(ScalaAnnotationData annotation) {
        registerAnnotationTypes(annotation.values().values());
        registerAnnotationType(annotation.annotationType());
    }

    private void registerAnnotationTypes(Iterable<?> values) {
        for (Object value : values) {
            registerAnnotationTypes(value);
        }
    }

    private void registerAnnotationTypes(@Nullable Object value) {
        if (value instanceof ScalaClassValueData classValueData) {
            registerAnnotationType(classValueData.annotationType());
        } else if (value instanceof ScalaAnnotationData annotationData) {
            registerAnnotationData(annotationData);
        } else if (value instanceof AnnotationValue<?> annotationValue) {
            registerAnnotationTypes(annotationValue.getValues().values());
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                registerAnnotationTypes(Array.get(value, i));
            }
        } else if (value instanceof Iterable<?> iterable) {
            registerAnnotationTypes(iterable);
        }
    }

    private void registerAnnotationType(@Nullable ScalaAnnotationTypeData annotationType) {
        if (annotationType == null) {
            return;
        }
        // An annotation type extracted from a symbol carries only the defaults harvested from
        // this compilation's trees, so a classpath annotation arrives here with none. Complete
        // them at the point of registration, which is the one path both the extracted and the
        // on-demand-resolved types go through.
        if (visitorContext instanceof ScalaVisitorContext scalaVisitorContext) {
            annotationType = scalaVisitorContext.completeAnnotationDefaults(annotationType);
        }
        if (annotationType == null || nativeAnnotationTypes.putIfAbsent(annotationType.name(), annotationType) != null) {
            return;
        }
        registerAnnotationTypes(annotationType.annotations());
        for (ScalaAnnotationMemberData member : annotationType.members().values()) {
            registerAnnotationTypes(member.annotations());
            registerAnnotationTypes(member.defaultValue());
        }
    }

    private void populateClassHierarchy(ScalaClassData classData, List<Object> hierarchy, Set<String> visited) {
        if (excludedHierarchyType(classData.name()) || !visited.add(classData.name())) {
            return;
        }
        for (ScalaTypeData interfaceType : classData.interfaces()) {
            populateTypeHierarchy(interfaceType, hierarchy, visited);
        }
        populateTypeHierarchy(classData.superType(), hierarchy, visited);
        hierarchy.add(classData);
    }

    private void populateTypeHierarchy(@Nullable ScalaTypeData typeData, List<Object> hierarchy, Set<String> visited) {
        if (typeData == null || typeData.primitive() || excludedHierarchyType(typeData.name())) {
            return;
        }
        Optional<ScalaClassData> sourceClassData = sourceClassData(typeData.name());
        if (sourceClassData.isPresent()) {
            populateClassHierarchy(sourceClassData.get(), hierarchy, visited);
            return;
        }
        if (!visited.add(typeData.name())) {
            return;
        }
        for (ScalaTypeData interfaceType : typeData.interfaces()) {
            populateTypeHierarchy(interfaceType, hierarchy, visited);
        }
        populateTypeHierarchy(typeData.superType(), hierarchy, visited);
        hierarchy.add(typeData);
    }

    private Optional<ScalaClassData> sourceClassData(String name) {
        if (visitorContext instanceof ScalaVisitorContext scalaVisitorContext) {
            return scalaVisitorContext.sourceClassData(name);
        }
        return Optional.empty();
    }

    /**
     * Supertypes that cannot contribute Micronaut metadata.
     *
     * <p>Every Scala class has `scala.Any` above it, and every case class additionally has
     * `scala.Product`, `scala.Equals` and `java.io.Serializable`. Walking them resolves
     * symbols and builds hierarchy entries for types that never carry an annotation --
     * measured at over 250 walks in a single run of this test suite. The answers do not
     * change; the work is simply not needed.
     *
     * <p>`scala.annotation.Annotation` and `StaticAnnotation` are deliberately *not* here:
     * they are a real part of a Scala annotation class's hierarchy.
     */
    private boolean excludedHierarchyType(String name) {
        return UNIVERSAL_SUPERTYPES.contains(name);
    }

    private String annotationTypeName(Object annotationType) {
        if (annotationType instanceof AnnotationTypeElement annotationTypeElement) {
            return annotationTypeElement.name();
        }
        if (annotationType instanceof String name) {
            return name;
        }
        return String.valueOf(annotationType);
    }

    @Nullable
    private String repeatableContainerName(AnnotationTypeElement annotationType) {
        ScalaAnnotationTypeData nativeType = annotationType.nativeType();
        if (nativeType != null && nativeType.repeatableContainerName() != null) {
            return nativeType.repeatableContainerName();
        }
        return null;
    }

    private record AnnotationTypeElement(String name, @Nullable ScalaAnnotationTypeData nativeType) {
    }

    private record AnnotationMemberElement(
        AnnotationTypeElement annotationType,
        @Nullable ScalaAnnotationMemberData nativeMember) {

        String name() {
            return Objects.requireNonNull(nativeMember).name();
        }
    }

    private record UnresolvedAnnotationMember(String name) {
    }
}
