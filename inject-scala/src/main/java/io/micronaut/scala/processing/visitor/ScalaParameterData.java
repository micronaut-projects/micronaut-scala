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
 * @param overriddenParameters The same-index parameters of the methods this one's method
 *                             overrides, least specific first
 * @param genericType The type with the declaring type's variables bound, or {@code null} when
 *     it is the declared one
 */
public record ScalaParameterData(
    String name,
    ScalaTypeData type,
    List<ScalaAnnotationData> annotations,
    @Nullable String defaultAccessor,
    boolean defaultAccessorStatic,
    Object nativeType,
    List<ScalaParameterData> overriddenParameters,
    @Nullable ScalaTypeData genericType
) implements ScalaAnnotatedElementData {

    /**
     * A parameter as declared, whose generic type is its declared one.
     *
     * @param name The name
     * @param type The type
     * @param annotations The annotations
     * @param defaultAccessor The default-value accessor, if any
     * @param defaultAccessorStatic Whether that accessor is static
     * @param nativeType The native Scala compiler object
     * @param overriddenParameters The same-index parameters of the overridden declarations
     */
    public ScalaParameterData(
        String name,
        ScalaTypeData type,
        List<ScalaAnnotationData> annotations,
        @Nullable String defaultAccessor,
        boolean defaultAccessorStatic,
        Object nativeType,
        List<ScalaParameterData> overriddenParameters) {
        this(name, type, annotations, defaultAccessor, defaultAccessorStatic, nativeType, overriddenParameters, null);
    }

    public ScalaParameterData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        overriddenParameters = overriddenParameters == null
            ? Collections.emptyList() : List.copyOf(overriddenParameters);
    }

    /**
     * A parameter without the ones it overrides, for the overridden parameters themselves.
     *
     * @param name The name
     * @param type The type
     * @param annotations The annotations
     * @param defaultAccessor The default-value accessor, if any
     * @param defaultAccessorStatic Whether that accessor is static
     * @param nativeType The native Scala compiler object
     */
    public ScalaParameterData(
        String name,
        ScalaTypeData type,
        List<ScalaAnnotationData> annotations,
        @Nullable String defaultAccessor,
        boolean defaultAccessorStatic,
        Object nativeType) {
        this(name, type, annotations, defaultAccessor, defaultAccessorStatic, nativeType, List.of());
    }

    public ScalaParameterData(String name, ScalaTypeData type, List<ScalaAnnotationData> annotations, Object nativeType) {
        this(name, type, annotations, null, false, nativeType, List.of());
    }

    /**
     * The type with the declaring type's variables bound as they were at the point of use;
     * {@link #type()} stays the declared one, which is what the parameter compiles to.
     *
     * @return The generic type
     */
    public ScalaTypeData resolvedType() {
        return genericType == null ? type : genericType;
    }
}
