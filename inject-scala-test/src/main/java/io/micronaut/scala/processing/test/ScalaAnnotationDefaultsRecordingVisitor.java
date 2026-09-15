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
package io.micronaut.scala.processing.test;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Records the member defaults of one annotation, read both ways the Element API offers them:
 * through {@link VisitorContext#getAnnotationDefaultValues(String)} and through the
 * {@link AnnotationValue} of the annotated class. The Scala counterpart of the
 * {@code DefaultsRecordingVisitor} in Core's {@code AnnotationMemberDefaultsSpec}.
 */
public final class ScalaAnnotationDefaultsRecordingVisitor implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<Recording> RECORDING = new ThreadLocal<>();

    /**
     * Executes a compilation recording the defaults of the given annotation as seen from the
     * given class.
     *
     * @param className The name of the class carrying the annotation
     * @param annotationName The annotation whose defaults to record
     * @param compilation The compilation
     * @return The recording
     */
    public static Recording record(String className, String annotationName, Runnable compilation) {
        Recording recording = new Recording(className, annotationName);
        RECORDING.set(recording);
        try {
            compilation.run();
            return recording;
        } finally {
            RECORDING.remove();
        }
    }

    /**
     * Renders the defaults in the language neutral form Core's spec compares against, so the
     * Scala result can be checked against the literal shared by the Java, Kotlin and Groovy
     * suites.
     *
     * @param values The defaults
     * @return The rendering
     */
    public static String describe(@Nullable Map<CharSequence, Object> values) {
        if (values == null) {
            return "<not recorded>";
        }
        Map<String, String> rendered = new TreeMap<>();
        values.forEach((key, value) -> rendered.put(key.toString(), render(value)));
        return rendered.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String render(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "["
                + Arrays.stream((Object[]) value).map(ScalaAnnotationDefaultsRecordingVisitor::render).collect(Collectors.joining(", "))
                + "]";
        }
        if (value instanceof AnnotationClassValue<?> classValue) {
            return "AnnotationClassValue(" + classValue.getName() + ")";
        }
        if (value instanceof AnnotationValue<?> annotationValue) {
            // Core's spec is Groovy, and Groovy prints a map as [k:v]
            String values = annotationValue.getValues().entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(", ", "[", "]"));
            return "AnnotationValue(" + annotationValue.getAnnotationName() + ", " + values + ")";
        }
        return value.getClass().getSimpleName() + "(" + value + ")";
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        @Nullable Recording recording = RECORDING.get();
        if (recording != null && element.getName().equals(recording.className)) {
            recording.fromContext = context.getAnnotationDefaultValues(recording.annotationName);
            AnnotationValue<?> annotation = element.getAnnotation(recording.annotationName);
            recording.fromAnnotation = annotation == null ? null : annotation.getDefaultValues();
        }
    }

    /**
     * What one compilation recorded.
     */
    public static final class Recording {
        private final String className;
        private final String annotationName;
        @Nullable
        private Map<CharSequence, Object> fromContext;
        @Nullable
        private Map<CharSequence, Object> fromAnnotation;

        private Recording(String className, String annotationName) {
            this.className = className;
            this.annotationName = annotationName;
        }

        /**
         * @return The defaults {@link VisitorContext#getAnnotationDefaultValues(String)} reported
         */
        @Nullable
        public Map<CharSequence, Object> getFromContext() {
            return fromContext;
        }

        /**
         * @return The defaults {@link AnnotationValue#getDefaultValues()} carried
         */
        @Nullable
        public Map<CharSequence, Object> getFromAnnotation() {
            return fromAnnotation;
        }
    }
}
