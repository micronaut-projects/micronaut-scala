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

/**
 * Compiler-derived class literal data used by annotation metadata.
 *
 * @param name The class name
 * @param annotationType The annotation type data if the class literal points at an annotation
 */
public record ScalaClassValueData(
    String name,
    @Nullable ScalaAnnotationTypeData annotationType
) {
}
