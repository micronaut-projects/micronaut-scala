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

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * A package element was allocated afresh on every {@code getPackage()} call, so a package
 * was as many elements as there were calls: an annotation added to one was invisible from
 * the next call, and from every other class in the same package.
 */
class ScalaPackageElementSpec extends AbstractScalaTypeElementSpec {

    private Map inspect(Closure<Map> consumer) {
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            found.putAll(consumer(context))
        }, {
            buildClassLoader('probe.First', '''
package probe

class First

class Second
''')
        })
        found
    }

    void 'a package is one element however it is reached'() {
        when:
        def found = inspect { context ->
            def first = context.getClassElement('probe.First').orElse(null)
            def second = context.getClassElement('probe.Second').orElse(null)
            [
                repeated: first.getPackage().is(first.getPackage()),
                shared: first.getPackage().is(second.getPackage()),
                name: first.getPackage().getName()
            ]
        }

        then:
        found.name == 'probe'
        found.repeated
        found.shared
    }

    void 'an annotation added to a package is visible from the package'() {
        when:
        def found = inspect { context ->
            def first = context.getClassElement('probe.First').orElse(null)
            first.getPackage().annotate('jakarta.inject.Named')
            [
                sameClass: first.getPackage().hasAnnotation('jakarta.inject.Named'),
                otherClass: context.getClassElement('probe.Second')
                        .map { it.getPackage().hasAnnotation('jakarta.inject.Named') }
                        .orElse(false)
            ]
        }

        then: 'the write used to go to an element thrown away on return'
        found.sameClass
        found.otherClass
    }

    void 'the simple name is the last segment'() {
        when:
        def found = inspect { context ->
            [simple: context.getClassElement('probe.First').orElse(null).getPackage().getSimpleName()]
        }

        then:
        found.simple == 'probe'
    }
}
