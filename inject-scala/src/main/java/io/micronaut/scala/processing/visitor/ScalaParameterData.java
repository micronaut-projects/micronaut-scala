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
import java.util.List;

/**
 * A Scala method or constructor parameter.
 *
 * @param name The parameter name
 * @param type The parameter type
 * @param annotations The annotations
 * @param nativeType The native Scala compiler object
 */
public record ScalaParameterData(
    String name,
    ScalaTypeData type,
    List<ScalaAnnotationData> annotations,
    Object nativeType
) implements ScalaAnnotatedElementData {

    public ScalaParameterData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
    }
}
