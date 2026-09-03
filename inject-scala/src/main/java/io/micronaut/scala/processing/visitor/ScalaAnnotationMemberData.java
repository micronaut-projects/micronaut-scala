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
 * Native Scala compiler data for an annotation member.
 *
 * @param name The member name
 * @param annotations The annotations declared on the member
 * @param defaultValue The member default value, if available
 * @param typeName The member type name
 * @param array Whether the member type is an array
 * @param classType Whether the member type is a class literal
 * @param enumType Whether the member type is an enum
 * @param annotationType Whether the member type is an annotation
 * @param nativeType The native Scala compiler object
 */
public record ScalaAnnotationMemberData(
    String name,
    List<ScalaAnnotationData> annotations,
    @Nullable Object defaultValue,
    String typeName,
    boolean array,
    boolean classType,
    boolean enumType,
    boolean annotationType,
    Object nativeType
) {

    public ScalaAnnotationMemberData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
    }
}
