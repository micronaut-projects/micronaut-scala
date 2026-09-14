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

import java.util.Collections;
import java.util.Map;

/**
 * A Scala annotation observed by the compiler plugin.
 *
 * @param name The annotation type name
 * @param values The annotation member values
 * @param annotationType The annotation type metadata
 */
public record ScalaAnnotationData(
    String name,
    Map<CharSequence, Object> values,
    @org.jspecify.annotations.Nullable ScalaAnnotationTypeData annotationType
) {

    public ScalaAnnotationData {
        values = values == null ? Collections.emptyMap() : Collections.unmodifiableMap(values);
    }

    public ScalaAnnotationData(String name, Map<CharSequence, Object> values) {
        this(name, values, null);
    }
}
