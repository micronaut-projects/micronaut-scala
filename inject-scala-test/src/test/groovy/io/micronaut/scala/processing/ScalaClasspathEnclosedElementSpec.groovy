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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.PropertyElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * A classpath element answered {@code getEnclosedElements} for constructors, methods and
 * fields only. A property query and a nested-class query both came back empty, so the two
 * element kinds disagreed about what the same class contains. Separately, a classpath
 * property read its metadata from the property but wrote annotations to the element, so an
 * annotation added to one was never visible again.
 */
class ScalaClasspathEnclosedElementSpec extends AbstractScalaTypeElementSpec {

    private static final String HOLDER = 'io.micronaut.scala.processing.fixtures.ExternalHolder'

    private Map inspect(Closure<Map> consumer) {
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            found.putAll(consumer(context.getClassElement(HOLDER).orElse(null)))
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })
        found
    }

    void 'a classpath type answers a property query'() {
        when:
        def found = inspect { element ->
            [names: element.getEnclosedElements(ElementQuery.of(PropertyElement)).collect { it.name }]
        }

        then:
        found.names == ['name']
    }

    void 'a classpath type answers a nested class query'() {
        when:
        def found = inspect { element ->
            [names: element.getEnclosedElements(ElementQuery.of(ClassElement)).collect { it.name }]
        }

        then:
        found.names == ['io.micronaut.scala.processing.fixtures.ExternalHolder$Nested']
    }

    void 'an annotation added to a classpath property is readable again'() {
        when:
        def found = inspect { element ->
            def property = element.getBeanProperties().find { it.name == 'name' }
            property.annotate('jakarta.inject.Inject')
            [
                onProperty: property.hasAnnotation('jakarta.inject.Inject'),
                names: property.getAnnotationNames()
            ]
        }

        then: 'the write went to metadata that nothing read'
        found.onProperty
        found.names.contains('jakarta.inject.Inject')
    }
}
