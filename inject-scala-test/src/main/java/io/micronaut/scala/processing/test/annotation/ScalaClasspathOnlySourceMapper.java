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
package io.micronaut.scala.processing.test.annotation;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationMapper;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.List;

/**
 * Mapper for {@link ScalaClasspathOnlySource}. Deliberately absent from this harness's
 * {@code META-INF/services}: the plugin loads its annotation services once for the JVM through
 * the loader that loaded it, and in this harness that loader sees the whole test classpath, so
 * a mapper registered here could never show whether one registered only on the compilation
 * classpath is applied.
 */
public final class ScalaClasspathOnlySourceMapper implements TypedAnnotationMapper<ScalaClasspathOnlySource> {

    @Override
    public List<AnnotationValue<?>> map(AnnotationValue<ScalaClasspathOnlySource> annotation, VisitorContext visitorContext) {
        return List.of(AnnotationValue.builder(ScalaMappedResult.class)
            .member("value", "classpath-only")
            .build());
    }

    @Override
    public Class<ScalaClasspathOnlySource> annotationType() {
        return ScalaClasspathOnlySource.class;
    }
}
