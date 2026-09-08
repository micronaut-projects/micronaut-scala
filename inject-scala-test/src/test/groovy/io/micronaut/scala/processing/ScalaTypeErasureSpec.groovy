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

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.WildcardElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Some Scala types are not JVM types. A union other than {@code A | Null}, an intersection,
 * and {@code Any} all compile to something else, and the model reported the source type:
 * {@code scala.Matchable} for a union and {@code scala.Any} for {@code Any}, neither of
 * which is a loadable class, and for an intersection the string
 * {@code "probe.Alpha & probe.Beta"}, which is not a class name at all. A bean definition
 * written from that names types no bytecode carries.
 *
 * <p>The expected values here are what {@code javap} reports for the same declarations
 * compiled by dotty 3.9.0.</p>
 */
class ScalaTypeErasureSpec extends AbstractScalaTypeElementSpec {

    private Map returnTypes(String body) {
        def element = buildClassElement('probe.Holder', """
package probe

import jakarta.inject.Singleton

trait Alpha { def a(): String }
trait Beta extends Alpha { def b(): String }

@Singleton
class Holder {
${body}
}
""")
        element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [it.name, it.getReturnType()] }
    }

    void 'a union erases to the type the JVM signature names'() {
        when:
        def types = returnTypes('''  def unrelated(): String | Int = "x"
  def related(): String | CharSequence = "x"''')

        then: 'javap: java.lang.Object union(); java.lang.CharSequence unionRelated();'
        types.unrelated.getName() == 'java.lang.Object'
        types.related.getName() == 'java.lang.CharSequence'
    }

    void 'an intersection erases to its dominant parent'() {
        when:
        def types = returnTypes('  def both(): Beta & Alpha = null')

        then: 'javap: probe.Beta both() -- and never a name containing an ampersand'
        types.both.getName() == 'probe.Beta'
        !types.both.getName().contains('&')
    }

    void 'Any and AnyVal are java.lang.Object'() {
        when:
        def types = returnTypes('''  def any(): Any = null
  def anyRef(): AnyRef = null
  def anyVal(): AnyVal = 1''')

        then:
        types.any.getName() == 'java.lang.Object'
        types.anyRef.getName() == 'java.lang.Object'
        types.anyVal.getName() == 'java.lang.Object'
    }

    void 'a union with Null is still a nullable type, not Object'() {
        when: 'this is the one union the model reads rather than erases'
        def types = returnTypes('  def maybe(): String | Null = null')

        then:
        types.maybe.getName() == 'java.lang.String'
    }

    void 'a wildcard is still a wildcard'() {
        when: 'an unbounded wildcard is Nothing .. Any, so it must not be read as Any'
        def element = buildClassElement('probe.Holder', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Holder {
  def values(): java.util.List[?] = null
}
''')
        def argument = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'values' }
                .getReturnType()
                .getFirstTypeArgument()
                .get()

        then:
        argument instanceof WildcardElement
    }
}
