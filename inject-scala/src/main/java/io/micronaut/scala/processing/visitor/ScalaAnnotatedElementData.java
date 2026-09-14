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

import java.util.List;

/**
 * Native Scala compiler data that can expose annotations.
 */
public interface ScalaAnnotatedElementData {

    /**
     * Returns the element name.
     *
     * @return The element name
     */
    String name();

    /**
     * Returns the annotations declared on the element.
     *
     * @return The annotations declared on the element
     */
    List<ScalaAnnotationData> annotations();

    /**
     * Returns the native Scala compiler object.
     *
     * @return The native Scala compiler object
     */
    Object nativeType();
}
