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

    private final VisitorContext visitorContext;
    private final Map<String, ScalaAnnotationTypeData> nativeAnnotationTypes = new LinkedHashMap<>();

    public ScalaAnnotationMetadataBuilder(VisitorContext visitorContext) {
        this.visitorContext = visitorContext;
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
                        Map<CharSequence, Object> values = new LinkedHashMap<>();
                        for (Map.Entry<? extends Object, ?> entry : readAnnotationRawValues(annotation).entrySet()) {
                            Object annotationMember = entry.getKey();
                            readAnnotationRawValues(
                                originatingElement,
                                annotationType.getName(),
                                annotationMember,
                                getAnnotationMemberName(annotationMember),
                                entry.getValue(),
                                values);
                        }
                        return Optional.of(AnnotationValue.builder(annotationType).members(values).build());
                    }
                }
            }
        }
        return Optional.empty();
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
        return Optional.ofNullable(nativeAnnotationTypes.get(annotationName))
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
        if (nativeType != null && nativeType.retentionPolicyName() != null) {
            return RetentionPolicy.valueOf(nativeType.retentionPolicyName());
        }
        return RetentionPolicy.RUNTIME;
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

    private boolean isValidDefaultValue(@Nullable Object defaultValue) {
        if (defaultValue == null) {
            return false;
        }
        return !(defaultValue instanceof String string) || !string.isEmpty();
    }

    private AnnotationTypeElement annotationType(ScalaAnnotationData annotation) {
        ScalaAnnotationTypeData nativeType = annotation.annotationType();
        if (nativeType != null) {
            registerAnnotationType(nativeType);
        } else {
            nativeType = nativeAnnotationTypes.get(annotation.name());
        }
        return new AnnotationTypeElement(annotation.name(), nativeType);
    }

    private AnnotationTypeElement annotationType(String annotationName) {
        ScalaAnnotationTypeData nativeType = nativeAnnotationTypes.get(annotationName);
        return new AnnotationTypeElement(annotationName, nativeType);
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

    private boolean excludedHierarchyType(String name) {
        return Object.class.getName().equals(name) || Enum.class.getName().equals(name);
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
