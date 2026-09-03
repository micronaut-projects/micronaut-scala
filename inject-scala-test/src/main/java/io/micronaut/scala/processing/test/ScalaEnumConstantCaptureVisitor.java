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
package io.micronaut.scala.processing.test;

import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Test visitor used to expose Scala enum constants visited by the compiler plugin.
 */
public final class ScalaEnumConstantCaptureVisitor implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<Consumer<EnumConstantElement>> ENUM_CONSTANT_CONSUMER = new ThreadLocal<>();

    /**
     * Executes a compilation with an enum constant consumer installed.
     *
     * @param consumer The consumer
     * @param runnable The compilation
     */
    public static void withConsumer(Consumer<EnumConstantElement> consumer, Runnable runnable) {
        ENUM_CONSTANT_CONSUMER.set(consumer);
        try {
            runnable.run();
        } finally {
            ENUM_CONSTANT_CONSUMER.remove();
        }
    }

    @Override
    public void visitEnumConstant(EnumConstantElement element, VisitorContext context) {
        @Nullable Consumer<EnumConstantElement> consumer = ENUM_CONSTANT_CONSUMER.get();
        if (consumer != null) {
            consumer.accept(element);
        }
    }
}
