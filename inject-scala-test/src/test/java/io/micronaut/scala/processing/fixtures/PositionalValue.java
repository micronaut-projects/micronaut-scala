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
package io.micronaut.scala.processing.fixtures;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A Java annotation that declares another member <em>before</em> {@code value}. Java's only
 * positional member is {@code value}, whatever order the members are declared in, so
 * {@code @PositionalValue("x")} must set {@code value} and not {@code other}.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface PositionalValue {

    /** @return a member declared before value */
    String other() default "";

    /** @return the positional member */
    String value();
}
