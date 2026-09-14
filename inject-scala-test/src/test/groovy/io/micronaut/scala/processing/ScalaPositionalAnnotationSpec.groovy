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

/**
 * A positional annotation argument carries no member name, so it is matched by index
 * against the annotation type's members. That is only correct while the member order the
 * extractor sees is the constructor's parameter order -- it comes from dotty's
 * {@code symbol.info.decls}, which is not the same thing by definition.
 */
class ScalaPositionalAnnotationSpec extends AbstractScalaTypeElementSpec {

    private static final String MARKER = '''
package probe

import scala.annotation.StaticAnnotation

class Marker(val first: String, val second: Int, val third: String) extends StaticAnnotation
'''

    void 'positional arguments match the constructor parameters in order'() {
        given:
        def element = buildClassElement('probe.Holder', MARKER + '''
@Marker("a", 2, "c")
class Holder
''')

        when:
        def values = element.getAnnotation('probe.Marker').getValues()

        then:
        values['first'] == 'a'
        values['second'] == 2
        values['third'] == 'c'
    }

    void 'positional and named arguments can be mixed'() {
        given:
        def element = buildClassElement('probe.Holder', MARKER + '''
@Marker("a", third = "c", second = 2)
class Holder
''')

        when:
        def values = element.getAnnotation('probe.Marker').getValues()

        then: 'the positional one is still the first parameter, not the first unfilled one'
        values['first'] == 'a'
        values['second'] == 2
        values['third'] == 'c'
    }

    void 'members declared in the body do not displace the constructor parameters'() {
        given:
        def element = buildClassElement('probe.Holder', '''
package probe

import scala.annotation.StaticAnnotation

class Marker(val alpha: String, val beta: String) extends StaticAnnotation {
  def zeta: String = "z"
  val gamma: String = "g"
}

@Marker("a", "b")
class Holder
''')

        when:
        def values = element.getAnnotation('probe.Marker').getValues()

        then: 'dotty lists constructor parameters before body members, which is what makes'
        values['alpha'] == 'a'
        values['beta'] == 'b'
    }

    void 'a Java annotation binds its positional argument to value'() {
        given: 'PositionalValue declares other() before value()'
        def element = buildClassElement('probe.Holder', '''
package probe

import io.micronaut.scala.processing.fixtures.PositionalValue

@PositionalValue("x")
class Holder
''')

        when:
        def values = element.getAnnotation('io.micronaut.scala.processing.fixtures.PositionalValue').getValues()

        then: 'index matching alone would have chosen other, the first declared member'
        values['value'] == 'x'
        !values.containsKey('other')
    }
}
