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

import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.function.Supplier;

/**
 * Test visitor that takes annotations off a visited class.
 *
 * <p>Removal is the half of element mutation nothing here could reach. {@link
 * ScalaElementMutationVisitor} only adds, and adding is forgiving: an annotation written twice
 * is the same as written once. Taking one away has to reach the same metadata the plugin built,
 * and a removal that silently misses leaves the bean exactly as it was.</p>
 */
public final class ScalaAnnotationRemovalVisitor implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<Removal> REMOVAL = new ThreadLocal<>();

    /**
     * Executes a compilation with this visitor removing the given annotation.
     *
     * @param mode The removal to perform
     * @param annotationName The annotation to remove
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T removing(Mode mode, String annotationName, Supplier<T> supplier) {
        return removing(mode, annotationName, null, supplier);
    }

    /**
     * Executes a compilation with this visitor removing the given annotation and adding another.
     *
     * @param mode The removal to perform
     * @param annotationName The annotation to remove
     * @param replacement The annotation to add in its place
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T removing(Mode mode, String annotationName, @Nullable String replacement, Supplier<T> supplier) {
        REMOVAL.set(new Removal(mode, annotationName, replacement));
        try {
            return supplier.get();
        } finally {
            REMOVAL.remove();
        }
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        Removal removal = REMOVAL.get();
        if (removal == null) {
            return;
        }
        switch (removal.mode()) {
            case REMOVE -> element.removeAnnotation(removal.annotationName());
            case REMOVE_IF -> element.removeAnnotationIf(
                annotationValue -> annotationValue.getAnnotationName().equals(removal.annotationName())
            );
            case REMOVE_STEREOTYPE -> element.removeStereotype(removal.annotationName());
            case REPLACE -> {
                element.removeAnnotation(removal.annotationName());
                if (removal.replacement() != null) {
                    element.annotate(removal.replacement());
                }
            }
            default -> throw new IllegalStateException("Unknown removal mode: " + removal.mode());
        }
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    /**
     * The kind of removal to perform.
     */
    public enum Mode {
        /** {@code removeAnnotation}. */
        REMOVE,
        /** {@code removeAnnotationIf}. */
        REMOVE_IF,
        /** {@code removeStereotype}. */
        REMOVE_STEREOTYPE,
        /** {@code removeAnnotation} followed by {@code annotate} with the replacement. */
        REPLACE
    }

    private record Removal(Mode mode, String annotationName, @Nullable String replacement) {
    }
}
