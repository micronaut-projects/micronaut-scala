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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Java annotation with a member of every value kind a class file can hold, for use on the
 * parameters and members of {@link ExternalJavaShapes}, whose annotations the compiler does
 * not read and the plugin reads from the class file itself.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD, ElementType.TYPE, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
public @interface ExternalTagged {

    /** @return An enum member */
    ExternalLevel level() default ExternalLevel.LOW;

    /** @return A class member */
    Class<?> type() default Object.class;

    /** @return An array member */
    String[] names() default {};

    /** @return An annotation member */
    ExternalNote note() default @ExternalNote;

    /** @return A member without a default */
    int weight();

    /** @return A member with a class-file constant default */
    long ceiling() default Long.MAX_VALUE;
}
