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

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Annotates the properties a type names in its own {@code @IgnoreProps}, the way
 * micronaut-serialization does for {@code @JsonIgnoreProperties}, and records once every type
 * was visited which properties and fields carry the annotation when read through each type.
 * The Scala counterpart of the two visitors in Core's {@code InheritedPropertyAnnotationMutationSpec}.
 */
public final class ScalaInheritedPropertyMutationVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The annotation naming the properties to annotate.
     */
    public static final String IGNORE_PROPS = "test.IgnoreProps";

    /**
     * The annotation added to the named properties.
     */
    public static final String IGNORED = "test.Ignored";

    private static final ThreadLocal<Recording> RECORDING = new ThreadLocal<>();

    /**
     * Executes a compilation with this visitor enabled.
     *
     * @param compilation The compilation
     * @return The recording
     */
    public static Recording record(Runnable compilation) {
        Recording recording = new Recording();
        RECORDING.set(recording);
        try {
            compilation.run();
            return recording;
        } finally {
            RECORDING.remove();
        }
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        @Nullable Recording recording = RECORDING.get();
        if (recording == null || !element.hasAnnotation(IGNORE_PROPS)) {
            return;
        }
        Set<String> ignored = Set.of(element.stringValues(IGNORE_PROPS));
        for (PropertyElement property : element.getBeanProperties()) {
            if (ignored.contains(property.getName())) {
                property.annotate(IGNORED);
            }
        }
        recording.elements.add(element);
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        @Nullable Recording recording = RECORDING.get();
        if (recording == null) {
            return;
        }
        for (ClassElement element : recording.elements) {
            recording.ignoredProperties.put(element.getName(), element.getBeanProperties().stream()
                .filter(property -> property.hasAnnotation(IGNORED))
                .map(PropertyElement::getName)
                .sorted()
                .toList());
            recording.ignoredFields.put(element.getName(), element.getEnclosedElements(ElementQuery.ALL_FIELDS).stream()
                .filter(field -> field.hasAnnotation(IGNORED))
                .map(field -> field.getName())
                .sorted()
                .toList());
        }
    }

    /**
     * What one compilation recorded, by type name.
     */
    public static final class Recording {
        private final List<ClassElement> elements = new ArrayList<>();
        private final Map<String, List<String>> ignoredProperties = new LinkedHashMap<>();
        private final Map<String, List<String>> ignoredFields = new LinkedHashMap<>();

        /**
         * @return The names of the properties carrying the annotation, by the type they were read through
         */
        public Map<String, List<String>> getIgnoredProperties() {
            return ignoredProperties;
        }

        /**
         * @return The names of the fields carrying the annotation, by the type they were read through
         */
        public Map<String, List<String>> getIgnoredFields() {
            return ignoredFields;
        }
    }
}
