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
package io.micronaut.scala.processing

import io.micronaut.inject.ast.GenericPlaceholderElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * A classpath type's generics were resolved through {@code ClassElement.of(...)}, Core's
 * reflective factory, which hands back its own element kinds. Those are immutable, so a
 * visitor annotating one fails with "does not support adding annotations at compilation
 * time", and they fail the {@code instanceof} checks the generics writers make against
 * this plugin's own element kinds.
 */
class ScalaLoadedGenericsSpec extends AbstractScalaTypeElementSpec {

    private Object typeArgumentOf(String className) {
        def captured = []
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            context.getClassElement(className).ifPresent { element ->
                captured.addAll(element.getTypeArguments().values())
            }
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })
        captured ? captured[0] : null
    }

    void 'a classpath type variable is this plugin own placeholder element'() {
        when:
        def argument = typeArgumentOf('java.util.List')

        then:
        argument instanceof GenericPlaceholderElement
        argument.getClass().getName().startsWith('io.micronaut.scala.')
        argument.getVariableName() == 'E'
    }

    void 'a classpath type variable can be annotated'() {
        given:
        def argument = typeArgumentOf('java.util.List')

        when: 'this is what a visitor does, and Core reflective elements refuse it'
        def annotated = argument.withAnnotationMetadata(argument.getAnnotationMetadata())

        then:
        annotated instanceof GenericPlaceholderElement
        annotated.getClass().getName().startsWith('io.micronaut.scala.')
    }
}
