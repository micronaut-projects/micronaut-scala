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
import io.micronaut.inject.ast.annotation.ElementAnnotationMetadata;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class SimpleElementAnnotationMetadata implements ElementAnnotationMetadata {

    private AnnotationMetadata annotationMetadata;
    private final boolean readOnly;
    private final ScalaAnnotationMetadataBuilder annotationMetadataBuilder;

    SimpleElementAnnotationMetadata(
        AnnotationMetadata annotationMetadata,
        boolean readOnly,
        ScalaAnnotationMetadataBuilder annotationMetadataBuilder) {
        this.annotationMetadata = annotationMetadata;
        this.readOnly = readOnly;
        this.annotationMetadataBuilder = annotationMetadataBuilder;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    @Override
    public <T extends Annotation> AnnotationMetadata annotate(String annotationType, Consumer<AnnotationValueBuilder<T>> consumer) {
        checkMutable();
        AnnotationValueBuilder<T> builder = AnnotationValue.builder(annotationType, annotationMetadataBuilder.getRetentionPolicy(annotationType));
        consumer.accept(builder);
        return annotate(builder.build());
    }

    @Override
    public <T extends Annotation> AnnotationMetadata annotate(AnnotationValue<T> annotationValue) {
        checkMutable();
        annotationMetadata = annotationMetadataBuilder.annotate(annotationMetadata, annotationValue);
        return annotationMetadata;
    }

    @Override
    public AnnotationMetadata removeAnnotation(String annotationType) {
        checkMutable();
        annotationMetadata = annotationMetadataBuilder.removeAnnotation(annotationMetadata, annotationType);
        return annotationMetadata;
    }

    @Override
    public <T extends Annotation> AnnotationMetadata removeAnnotationIf(Predicate<AnnotationValue<T>> predicate) {
        checkMutable();
        annotationMetadata = annotationMetadataBuilder.removeAnnotationIf(annotationMetadata, predicate);
        return annotationMetadata;
    }

    @Override
    public AnnotationMetadata removeStereotype(String annotationType) {
        checkMutable();
        annotationMetadata = annotationMetadataBuilder.removeStereotype(annotationMetadata, annotationType);
        return annotationMetadata;
    }

    private void checkMutable() {
        if (readOnly) {
            throw new UnsupportedOperationException("Annotation metadata is read only");
        }
    }
}
