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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Scala compiler data for an annotation type.
 *
 * @param name The annotation type name
 * @param annotations The annotations declared on the annotation type
 * @param members The annotation members by name
 * @param retentionPolicyName The retention policy enum name, if known
 * @param repeatableContainerName The repeatable container annotation name, if known
 * @param nativeType The native Scala compiler object
 */
public record ScalaAnnotationTypeData(
    String name,
    List<ScalaAnnotationData> annotations,
    Map<String, ScalaAnnotationMemberData> members,
    @Nullable String retentionPolicyName,
    @Nullable String repeatableContainerName,
    Object nativeType
) {

    public ScalaAnnotationTypeData {
        annotations = annotations == null ? Collections.emptyList() : List.copyOf(annotations);
        members = members == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }
}
