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

import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotatingVisitor
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

import java.util.function.Supplier

/**
 * The annotation-type mirror map is populated as elements are visited, so an annotation
 * added programmatically -- by {@code element.annotate(...)}, a mapper, or a
 * bean-definition builder -- had no mirror unless something else in the compilation
 * happened to use it first. Its meta-annotations were then silently not processed, which
 * made stereotype resolution depend on compilation order.
 */
class ScalaAnnotationMirrorSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package probe

import jakarta.inject.Singleton

@Singleton
class Plain
'''

    private Map annotateWith(String annotationName) {
        def captured = [:]
        ScalaAnnotatingVisitor.withClassAnnotation(annotationName, {
            ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
                def element = context.getClassElement('probe.Plain').orElse(null)
                if (element != null) {
                    captured.names = element.getAnnotationNames()
                    captured.qualifier = element.hasStereotype(AnnotationUtil.QUALIFIER)
                }
            }, {
                buildClassLoader('probe.Plain', SOURCE)
            })
        } as Supplier)
        captured
    }

    void 'an annotation added by a visitor resolves its stereotypes'() {
        when: 'the compilation never mentions this annotation, so it was never registered'
        def captured = annotateWith('io.micronaut.scala.processing.fixtures.Location')

        then: '@Location is meta-annotated @Qualifier, and that must be resolved'
        captured.qualifier
    }

    void 'a repeatable annotation added by a visitor is wrapped in its container'() {
        when:
        def captured = annotateWith('io.micronaut.scala.processing.fixtures.Location')

        then: 'resolving the mirror is also what makes the @Repeatable container known'
        captured.names.contains('io.micronaut.scala.processing.fixtures.Locations')
    }

    void 'an annotation that does not exist is not resolved'() {
        when:
        def captured = annotateWith('does.not.Exist')

        then: 'an unresolvable name must not fail the compilation, only carry no stereotypes'
        captured.names.contains('does.not.Exist')
        !captured.qualifier
    }
}
