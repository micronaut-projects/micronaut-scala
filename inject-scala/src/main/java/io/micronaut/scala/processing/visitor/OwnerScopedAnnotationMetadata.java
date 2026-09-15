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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The annotation metadata of an inherited member read through one owning type, the way Core's
 * {@code OwnerCachedAnnotationMetadata} keeps it: the metadata mutated for the owning type when
 * there is one, otherwise the metadata the member shares with every type it is read through.
 *
 * <p>A member's mutations are shared: an inherited field is the same field whichever class it is
 * read through, so annotating it through one is visible through all. A bean property is not --
 * it is resolved for the type it is read through, and annotating the property of a super type
 * must not annotate the same property read through a subclass. The two views of one
 * {@link Scope} tell the cases apart: a mutation through the {@code ownerMutation} view, the one
 * a property writes through, first copies the shared metadata for the owning type, and from
 * then on both views of that owner read and write the copy.</p>
 */
final class OwnerScopedAnnotationMetadata implements ElementAnnotationMetadata {

    private final Scope scope;
    private final boolean ownerMutation;

    private OwnerScopedAnnotationMetadata(Scope scope, boolean ownerMutation) {
        this.scope = scope;
        this.ownerMutation = ownerMutation;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return scope.current();
    }

    @Override
    public <T extends Annotation> AnnotationMetadata annotate(String annotationType, Consumer<AnnotationValueBuilder<T>> consumer) {
        AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType, scope.annotationMetadataBuilder.getRetentionPolicy(annotationType));
        consumer.accept(builder);
        return annotate(builder.build());
    }

    @Override
    public <T extends Annotation> AnnotationMetadata annotate(AnnotationValue<T> annotationValue) {
        return scope.mutate(ownerMutation, metadata -> scope.annotationMetadataBuilder.annotate(metadata, annotationValue));
    }

    @Override
    public AnnotationMetadata removeAnnotation(String annotationType) {
        return scope.mutate(ownerMutation, metadata -> scope.annotationMetadataBuilder.removeAnnotation(metadata, annotationType));
    }

    @Override
    public <T extends Annotation> AnnotationMetadata removeAnnotationIf(Predicate<AnnotationValue<T>> predicate) {
        return scope.mutate(ownerMutation, metadata -> scope.annotationMetadataBuilder.removeAnnotationIf(metadata, predicate));
    }

    @Override
    public AnnotationMetadata removeStereotype(String annotationType) {
        return scope.mutate(ownerMutation, metadata -> scope.annotationMetadataBuilder.removeStereotype(metadata, annotationType));
    }

    /**
     * The metadata of one member as read through one owning type: the shared metadata, and the
     * copy the owning type mutates once a mutation belongs to it.
     */
    static final class Scope {

        private final MutableAnnotationMetadata shared;
        private final ScalaAnnotationMetadataBuilder annotationMetadataBuilder;
        @Nullable
        private MutableAnnotationMetadata owned;

        Scope(MutableAnnotationMetadata shared, ScalaAnnotationMetadataBuilder annotationMetadataBuilder) {
            this.shared = shared;
            this.annotationMetadataBuilder = annotationMetadataBuilder;
        }

        /**
         * The view a mutation made on the member itself writes through.
         *
         * @return The view
         */
        ElementAnnotationMetadata memberView() {
            return new OwnerScopedAnnotationMetadata(this, false);
        }

        /**
         * The view a mutation made on the member as a component of a bean property writes
         * through.
         *
         * @return The view
         */
        ElementAnnotationMetadata propertyComponentView() {
            return new OwnerScopedAnnotationMetadata(this, true);
        }

        private AnnotationMetadata current() {
            return owned != null ? owned : shared;
        }

        private AnnotationMetadata mutate(boolean ownerMutation, UnaryOperator<AnnotationMetadata> mutation) {
            if (ownerMutation && owned == null) {
                owned = MutableAnnotationMetadata.of(shared);
            }
            return mutation.apply(current());
        }
    }
}
