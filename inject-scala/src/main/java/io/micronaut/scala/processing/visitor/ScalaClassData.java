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
 * A Scala class observed by the compiler plugin.
 *
 * @param name The class name
 * @param annotations The annotations
 * @param modifiers The modifiers
 * @param annotationType Whether this class is an annotation type
 * @param interfaceType Whether this class is an interface or trait
 * @param enumType Whether this class is an enum
 * @param typeParameters The declared generic placeholders
 * @param superType The super type
 * @param interfaces The interface types
 * @param constructors The constructors
 * @param methods The methods
 * @param fields The fields
 * @param properties The properties
 * @param enclosingTypeName The enclosing type name for inner classes
 * @param nativeType The native Scala compiler object
 */
public record ScalaClassData(
    String name,
    List<ScalaAnnotationData> annotations,
    Set<ElementModifier> modifiers,
    boolean annotationType,
    boolean interfaceType,
    boolean enumType,
    List<ScalaTypeData> typeParameters,
    ScalaTypeData superType,
    List<ScalaTypeData> interfaces,
    List<ScalaMethodData> constructors,
    List<ScalaMethodData> methods,
    List<ScalaFieldData> fields,
    List<ScalaPropertyData> properties,
    String enclosingTypeName,
    Object nativeType
) implements ScalaAnnotatedElementData {

    public ScalaClassData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        modifiers = modifiers == null ? Collections.emptySet() : Set.copyOf(modifiers);
        typeParameters = typeParameters == null ? Collections.emptyList() : List.copyOf(typeParameters);
        interfaces = interfaces == null ? Collections.emptyList() : List.copyOf(interfaces);
        constructors = constructors == null ? Collections.emptyList() : List.copyOf(constructors);
        methods = methods == null ? Collections.emptyList() : List.copyOf(methods);
        fields = fields == null ? Collections.emptyList() : List.copyOf(fields);
        properties = properties == null ? Collections.emptyList() : List.copyOf(properties);
    }
}
