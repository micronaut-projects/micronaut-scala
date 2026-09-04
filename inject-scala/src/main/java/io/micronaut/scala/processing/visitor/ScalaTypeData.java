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
import java.util.Map;

/**
 * A reduced Scala type model used by the Element API wrappers.
 *
 * @param name The JVM type name
 * @param primitive Whether the type is primitive
 * @param arrayDimensions The array dimensions
 * @param interfaceType Whether the type is an interface
 * @param typeArguments The type arguments
 * @param superType The compiler-resolved super type
 * @param interfaces The compiler-resolved interface types
 * @param annotations The compiler-resolved annotations
 * @param annotatedTypeUse Whether the type reference has type-use annotations
 * @param nativeType The native Scala compiler object
 * @param genericPlaceholder Whether this type is a generic placeholder
 * @param variableName The generic placeholder variable name
 * @param bounds The generic placeholder bounds
 * @param wildcard Whether this type is a wildcard
 * @param upperBounds The wildcard upper bounds
 * @param lowerBounds The wildcard lower bounds
 */
@SuppressWarnings("ParameterNumber")
public record ScalaTypeData(
    String name,
    boolean primitive,
    int arrayDimensions,
    boolean interfaceType,
    Map<String, ScalaTypeData> typeArguments,
    @Nullable ScalaTypeData superType,
    List<ScalaTypeData> interfaces,
    List<ScalaAnnotationData> annotations,
    boolean annotatedTypeUse,
    @Nullable Object nativeType,
    boolean genericPlaceholder,
    @Nullable String variableName,
    List<ScalaTypeData> bounds,
    boolean wildcard,
    List<ScalaTypeData> upperBounds,
    List<ScalaTypeData> lowerBounds
) implements ScalaAnnotatedElementData {

    public ScalaTypeData {
        typeArguments = typeArguments == null ? Collections.emptyMap() : Collections.unmodifiableMap(typeArguments);
        interfaces = interfaces == null ? Collections.emptyList() : List.copyOf(interfaces);
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        bounds = bounds == null ? Collections.emptyList() : List.copyOf(bounds);
        upperBounds = upperBounds == null ? Collections.emptyList() : List.copyOf(upperBounds);
        lowerBounds = lowerBounds == null ? Collections.emptyList() : List.copyOf(lowerBounds);
    }

    public ScalaTypeData(
        String name,
        boolean primitive,
        int arrayDimensions,
        boolean interfaceType,
        Map<String, ScalaTypeData> typeArguments,
        @Nullable ScalaTypeData superType,
        List<ScalaTypeData> interfaces,
        List<ScalaAnnotationData> annotations,
        boolean annotatedTypeUse,
        @Nullable Object nativeType
    ) {
        this(name, primitive, arrayDimensions, interfaceType, typeArguments, superType, interfaces, annotations, annotatedTypeUse, nativeType, false, null, Collections.emptyList(),
            false, Collections.emptyList(), Collections.emptyList());
    }

    public ScalaTypeData(
        String name,
        boolean primitive,
        int arrayDimensions,
        boolean interfaceType,
        Map<String, ScalaTypeData> typeArguments,
        @Nullable ScalaTypeData superType,
        List<ScalaTypeData> interfaces,
        List<ScalaAnnotationData> annotations,
        boolean annotatedTypeUse,
        @Nullable Object nativeType,
        boolean genericPlaceholder,
        @Nullable String variableName,
        List<ScalaTypeData> bounds
    ) {
        this(name, primitive, arrayDimensions, interfaceType, typeArguments, superType, interfaces, annotations, annotatedTypeUse, nativeType, genericPlaceholder, variableName, bounds,
            false, Collections.emptyList(), Collections.emptyList());
    }

    public ScalaTypeData(
        String name,
        boolean primitive,
        int arrayDimensions,
        boolean interfaceType,
        Map<String, ScalaTypeData> typeArguments,
        @Nullable ScalaTypeData superType,
        List<ScalaTypeData> interfaces
    ) {
        this(name, primitive, arrayDimensions, interfaceType, typeArguments, superType, interfaces, Collections.emptyList(), false, null, false, null, Collections.emptyList(),
            false, Collections.emptyList(), Collections.emptyList());
    }

    public ScalaTypeData(
        String name,
        boolean primitive,
        int arrayDimensions,
        boolean interfaceType,
        Map<String, ScalaTypeData> typeArguments
    ) {
        this(name, primitive, arrayDimensions, interfaceType, typeArguments, null, Collections.emptyList());
    }

    public ScalaTypeData withArrayDimensions(int dimensions) {
        return new ScalaTypeData(name, primitive, dimensions, interfaceType, typeArguments, superType, interfaces, annotations, annotatedTypeUse, nativeType, genericPlaceholder,
            variableName, bounds, wildcard, upperBounds, lowerBounds);
    }
}
