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

import io.micronaut.core.annotation.AnnotationValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A classpath member presented to {@link ScalaAnnotationMetadataBuilder} in the same shape as
 * a member extracted from source.
 *
 * <p>Annotations read from a class file used to be pushed straight into a
 * {@code MutableAnnotationMetadata}, which walks no meta-annotations: on a classpath type
 * {@code hasStereotype(SCOPE)} and {@code hasStereotype(QUALIFIER)} were false, {@code @AliasFor}
 * never resolved, the mapper and transformer SPI never ran, and repeatable containers were not
 * unwrapped -- so the same type answered differently depending on whether it came from source or
 * from the classpath. Adapting the raw values into {@link ScalaAnnotationData} lets both kinds
 * share one pipeline.
 *
 * @param name The element name
 * @param annotations The adapted annotations
 * @param nativeType The reflective member the annotations were read from
 */
record LoadedAnnotatedElementData(
    String name,
    List<ScalaAnnotationData> annotations,
    Object nativeType
) implements ScalaAnnotatedElementData {

    /**
     * Adapts raw class-file annotations, resolving each annotation type through the compiler so
     * its meta-annotations, members and retention are known.
     *
     * @param name The element name
     * @param nativeType The reflective member
     * @param values The raw annotation values
     * @param visitorContext The visitor context
     * @return The adapted element
     */
    static LoadedAnnotatedElementData of(
        String name,
        Object nativeType,
        List<AnnotationValue<?>> values,
        ScalaVisitorContext visitorContext) {
        List<ScalaAnnotationData> annotations = new ArrayList<>(values.size());
        for (AnnotationValue<?> value : values) {
            annotations.add(toAnnotationData(value, visitorContext));
        }
        return new LoadedAnnotatedElementData(name, List.copyOf(annotations), nativeType);
    }

    private static ScalaAnnotationData toAnnotationData(AnnotationValue<?> value, ScalaVisitorContext visitorContext) {
        Map<CharSequence, Object> members = new LinkedHashMap<>();
        value.getValues().forEach((member, memberValue) ->
            members.put(member, toMemberValue(memberValue, visitorContext)));
        return new ScalaAnnotationData(
            value.getAnnotationName(),
            members,
            visitorContext.resolveAnnotationType(value.getAnnotationName())
        );
    }

    /**
     * Nested annotations have to be adapted too, or their own types stay unresolved and the
     * same loss of meta-annotations happens one level down.
     */
    private static Object toMemberValue(Object value, ScalaVisitorContext visitorContext) {
        if (value instanceof AnnotationValue<?> annotationValue) {
            return toAnnotationData(annotationValue, visitorContext);
        }
        if (value != null && value.getClass().isArray() && !value.getClass().getComponentType().isPrimitive()) {
            int length = Array.getLength(value);
            Object[] adapted = new Object[length];
            boolean nested = false;
            for (int i = 0; i < length; i++) {
                Object element = Array.get(value, i);
                adapted[i] = toMemberValue(element, visitorContext);
                nested |= adapted[i] instanceof ScalaAnnotationData;
            }
            if (!nested) {
                return value;
            }
            ScalaAnnotationData[] annotations = new ScalaAnnotationData[length];
            System.arraycopy(adapted, 0, annotations, 0, length);
            return annotations;
        }
        return value;
    }
}
