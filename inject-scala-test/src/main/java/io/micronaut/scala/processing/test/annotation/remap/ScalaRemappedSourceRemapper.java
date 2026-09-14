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
package io.micronaut.scala.processing.test.annotation.remap;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.scala.processing.test.annotation.ScalaMappedResult;

import java.util.List;

/**
 * Remapper for {@link ScalaRemappedSource}.
 */
public final class ScalaRemappedSourceRemapper implements AnnotationRemapper {

    @Override
    public String getPackageName() {
        return "io.micronaut.scala.processing.test.annotation.remap";
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        if (annotation.getAnnotationName().equals(ScalaRemappedSource.class.getName())) {
            return List.of(AnnotationValue.builder(ScalaMappedResult.class)
                .member("value", "remapper")
                .build());
        }
        return List.of(annotation);
    }
}
