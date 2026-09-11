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
 * A Scala field.
 *
 * @param name The field name
 * @param type The field type
 * @param annotations The annotations
 * @param modifiers The modifiers
 * @param enumConstant Whether this field is an enum constant
 * @param constantValue The compile-time constant value, if any
 * @param nativeType The native Scala compiler object
 * @param genericType The type with the declaring type's variables bound, or {@code null} when
 *     it is the declared one
 */
public record ScalaFieldData(
    String name,
    ScalaTypeData type,
    List<ScalaAnnotationData> annotations,
    Set<ElementModifier> modifiers,
    boolean enumConstant,
    @Nullable Object constantValue,
    Object nativeType,
    @Nullable ScalaTypeData genericType
) implements ScalaAnnotatedElementData {

    /**
     * A field as declared, whose generic type is its declared one.
     *
     * @param name The name
     * @param type The type
     * @param annotations The annotations
     * @param modifiers The modifiers
     * @param enumConstant Whether this is an enum constant
     * @param constantValue The constant value, if any
     * @param nativeType The native Scala compiler object
     */
    public ScalaFieldData(
        String name,
        ScalaTypeData type,
        List<ScalaAnnotationData> annotations,
        Set<ElementModifier> modifiers,
        boolean enumConstant,
        @Nullable Object constantValue,
        Object nativeType) {
        this(name, type, annotations, modifiers, enumConstant, constantValue, nativeType, null);
    }

    public ScalaFieldData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        modifiers = modifiers == null ? Collections.emptySet() : Set.copyOf(modifiers);
    }

    /**
     * The type with the declaring type's variables bound as they were at the point of use;
     * {@link #type()} stays the declared one, which is what the field compiles to.
     *
     * @return The generic type
     */
    public ScalaTypeData resolvedType() {
        return genericType == null ? type : genericType;
    }

}
