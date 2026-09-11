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
import org.jspecify.annotations.Nullable;

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
 * @param overriddenMethods The declarations this method overrides, least specific first
 * @param genericReturnType The return type with the declaring type's variables bound, or
 *     {@code null} when it is the declared one
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
    Object nativeType,
    List<ScalaMethodData> overriddenMethods,
    @Nullable ScalaTypeData genericReturnType
) implements ScalaAnnotatedElementData {

    /**
     * A method as declared, whose generic return type is its declared one.
     *
     * @param name The method name
     * @param returnType The return type
     * @param parameters The parameters
     * @param typeParameters The type parameters
     * @param thrownTypes The declared thrown types
     * @param annotations The annotations
     * @param modifiers The modifiers
     * @param constructor Whether this is a constructor
     * @param nativeType The native Scala compiler object
     * @param overriddenMethods The declarations this method overrides
     */
    public ScalaMethodData(
        String name,
        ScalaTypeData returnType,
        List<ScalaParameterData> parameters,
        List<ScalaTypeData> typeParameters,
        List<ScalaTypeData> thrownTypes,
        List<ScalaAnnotationData> annotations,
        Set<ElementModifier> modifiers,
        boolean constructor,
        Object nativeType,
        List<ScalaMethodData> overriddenMethods) {
        this(name, returnType, parameters, typeParameters, thrownTypes, annotations, modifiers, constructor, nativeType, overriddenMethods, null);
    }

    /**
     * A method without the declarations it overrides, for the overridden ones themselves.
     *
     * @param name The method name
     * @param returnType The return type
     * @param parameters The parameters
     * @param typeParameters The type parameters
     * @param thrownTypes The declared thrown types
     * @param annotations The annotations
     * @param modifiers The modifiers
     * @param constructor Whether this is a constructor
     * @param nativeType The native Scala compiler object
     */
    public ScalaMethodData(
        String name,
        ScalaTypeData returnType,
        List<ScalaParameterData> parameters,
        List<ScalaTypeData> typeParameters,
        List<ScalaTypeData> thrownTypes,
        List<ScalaAnnotationData> annotations,
        Set<ElementModifier> modifiers,
        boolean constructor,
        Object nativeType) {
        this(name, returnType, parameters, typeParameters, thrownTypes, annotations, modifiers, constructor, nativeType, List.of());
    }

    public ScalaMethodData {
        overriddenMethods = overriddenMethods == null ? Collections.emptyList() : List.copyOf(overriddenMethods);
        parameters = parameters == null ? Collections.emptyList() : List.copyOf(parameters);
        typeParameters = typeParameters == null ? Collections.emptyList() : List.copyOf(typeParameters);
        thrownTypes = thrownTypes == null ? Collections.emptyList() : List.copyOf(thrownTypes);
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        modifiers = modifiers == null ? Collections.emptySet() : Set.copyOf(modifiers);
    }

    /**
     * The return type with the type variables of the declaring type bound as they were at the
     * point of use: {@code Book} for {@code find} inherited through {@code Repo[Book, Long]}.
     * {@link #returnType()} stays the declared {@code E}, which is what the method compiles
     * to and what an override of it has to be declared as.
     *
     * @return The generic return type
     */
    public ScalaTypeData resolvedReturnType() {
        return genericReturnType == null ? returnType : genericReturnType;
    }

}
