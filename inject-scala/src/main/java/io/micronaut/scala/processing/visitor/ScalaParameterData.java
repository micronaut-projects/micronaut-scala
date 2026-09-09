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

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * A Scala method or constructor parameter.
 *
 * @param name The parameter name
 * @param type The parameter type
 * @param annotations The annotations
 * @param defaultAccessor The name of the generated accessor supplying this parameter's
 *     default value, or {@code null} when it has no default or the accessor cannot be
 *     reached from the call site
 * @param defaultAccessorStatic Whether that accessor is a static method of the declaring
 *     class rather than an instance method
 * @param nativeType The native Scala compiler object
 */
public record ScalaParameterData(
    String name,
    ScalaTypeData type,
    List<ScalaAnnotationData> annotations,
    @Nullable String defaultAccessor,
    boolean defaultAccessorStatic,
    Object nativeType
) implements ScalaAnnotatedElementData {

    public ScalaParameterData(String name, ScalaTypeData type, List<ScalaAnnotationData> annotations, Object nativeType) {
        this(name, type, annotations, null, false, nativeType);
    }

    public ScalaParameterData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
    }
}
