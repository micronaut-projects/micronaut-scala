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

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.writer.ParameterDefaultValueProvider;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Supplies a Scala default argument as an expression the caller evaluates.
 *
 * <p>Scala compiles a default argument to a zero-argument accessor rather than to anything in
 * the method signature: {@code class Greeter(greeting: String = "hello")} emits a static
 * {@code $lessinit$greater$default$1()} on the class, and {@code def repeat(word: String,
 * count: Int = 3)} an instance {@code repeat$default$2()}. Nothing about the parameter itself
 * changes, which is why a default argument was previously invisible and the parameter was
 * treated as required.</p>
 *
 * <p>This is Scala's whole convention: there is no bitmask and no synthetic overload, so the
 * caller-side SPI fits it exactly, and a Kotlin-style calling convention would not.</p>
 */
@Internal
public final class ScalaParameterDefaultValueProvider implements ParameterDefaultValueProvider {

    /** The synthetic marker the extractor puts on a parameter with a reachable default. */
    private static final String MARKER = "io.micronaut.scala.ScalaDefaultValue";
    private static final String ACCESSOR = "accessor";
    private static final String STATIC = "static";

    @Override
    public boolean supports(ParameterElement parameter) {
        // Deliberately not `instanceof ScalaParameterElement`. Core loads this provider with
        // *Core's* classloader, which is not always the plugin's isolated one -- when it is
        // not, the two `ScalaParameterElement` classes have the same name and are different
        // types, so `instanceof` is false and the default silently never applies. Annotation
        // metadata is data rather than a type and crosses that boundary.
        return parameter.getAnnotationMetadata().hasAnnotation(MARKER);
    }

    @Override
    public Optional<ExpressionDef> defaultValueExpression(ParameterElement parameter, @Nullable ExpressionDef target) {
        String accessor = parameter.getAnnotationMetadata().stringValue(MARKER, ACCESSOR).orElse(null);
        if (accessor == null) {
            return Optional.empty();
        }
        ClassElement declaringType = parameter.getMethodElement() == null
            ? null
            : parameter.getMethodElement().getDeclaringType();
        if (declaringType == null) {
            return Optional.empty();
        }
        TypeDef returnType = TypeDef.erasure(parameter.getType());
        if (parameter.getAnnotationMetadata().booleanValue(MARKER, STATIC).orElse(false)) {
            return Optional.of(
                ClassTypeDef.of(declaringType.getName()).invokeStatic(accessor, returnType, List.of())
            );
        }
        if (target == null) {
            // An instance accessor with no receiver cannot be called. Reporting no expression
            // leaves the parameter to core's fallback rather than emitting a call that would
            // not link.
            return Optional.empty();
        }
        return Optional.of(target.invoke(accessor, returnType, List.of()));
    }
}
