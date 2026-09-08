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
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * Ported from the Java module's {@code ClassElementSpec} "test canonical name". Neither
 * element kind overrode {@code getCanonicalName()}, so core's default answered with the
 * binary name -- {@code nest.Outer$Inner} where the Java module answers
 * {@code nest.Outer.Inner}. That name reaches users in diagnostics and in introspection
 * naming.
 */
class ScalaCanonicalNameSpec extends AbstractScalaTypeElementSpec {

    void 'a nested type has a dotted canonical name'() {
        given:
        def element = buildClassElement('nest.Outer', '''
package nest

class Outer {
  class Inner
}
''')

        expect:
        element.getCanonicalName() == 'nest.Outer'

        and:
        element.getEnclosedElements(ElementQuery.of(ClassElement)).first().with {
            it.getName() == 'nest.Outer$Inner' && it.getCanonicalName() == 'nest.Outer.Inner'
        }
    }

    void 'the simple name is still the binary name without the package'() {
        given:
        def element = buildClassElement('nest.Outer', '''
package nest

class Outer {
  class Inner
}
''')

        expect: 'this matches the Java module, which does not dot-separate here'
        element.getSimpleName() == 'Outer'
        element.getEnclosedElements(ElementQuery.of(ClassElement)).first().getSimpleName() == 'Outer$Inner'
    }

    void 'a classpath type answers with its canonical name too'() {
        when:
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            def entry = context.getClassElement('java.util.Map$Entry').orElse(null)
            found.name = entry?.getName()
            found.canonical = entry?.getCanonicalName()
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })

        then:
        found.name == 'java.util.Map$Entry'
        found.canonical == 'java.util.Map.Entry'
    }
}
