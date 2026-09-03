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

import io.micronaut.inject.ast.ElementModifier;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A Scala method or constructor.
 *
 * @param name The method name
 * @param returnType The return type
 * @param parameters The parameters
 * @param typeParameters The declared generic placeholders
 * @param thrownTypes The declared thrown types
 * @param annotations The annotations
 * @param modifiers The modifiers
 * @param constructor Whether this represents a constructor
 * @param nativeType The native Scala compiler object
 */
public record ScalaMethodData(
    String name,
    ScalaTypeData returnType,
    List<ScalaParameterData> parameters,
    List<ScalaTypeData> typeParameters,
    List<ScalaTypeData> thrownTypes,
    List<ScalaAnnotationData> annotations,
    Set<ElementModifier> modifiers,
    boolean constructor,
    Object nativeType
) implements ScalaAnnotatedElementData {

    public ScalaMethodData {
        parameters = parameters == null ? Collections.emptyList() : List.copyOf(parameters);
        typeParameters = typeParameters == null ? Collections.emptyList() : List.copyOf(typeParameters);
        thrownTypes = thrownTypes == null ? Collections.emptyList() : List.copyOf(thrownTypes);
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        modifiers = modifiers == null ? Collections.emptySet() : Set.copyOf(modifiers);
    }
}
