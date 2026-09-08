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

import io.micronaut.inject.ast.ConstructorElement
import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.MethodElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * {@code ElementQuery} parity, ported from the Java module's
 * {@code io.micronaut.visitors.ClassElementSpec} -- "find matching methods", "find matching
 * constructors" and "find enum fields" -- asking the same questions of the equivalent Scala
 * shapes.
 *
 * <p>Where Scala answers differently the difference is stated in the feature, because it is
 * the language and not the adapter: Scala has no package-private members and no statics on a
 * class, {@code protected} compiles to public, and a {@code val}/{@code var} is a private
 * field behind accessors rather than an accessible field.</p>
 */
class ScalaElementQueryParitySpec extends AbstractScalaTypeElementSpec {

    private static final String HIERARCHY = '''
package elementquery

trait SomeInt {
  def itfeMethod(): Boolean = true
  def publicMethod(): Boolean
}

trait AnotherInterface {
  def publicMethod(): Boolean
}

class SuperType {
  var s1: Boolean = false
  private var s2: Boolean = false
  private def privateMethod(): Boolean = true
  def publicMethod(): Boolean = true
  def otherSuper(): Boolean = true
}

class Test extends SuperType with AnotherInterface with SomeInt {
  var t1: Boolean = false
  private var t2: Boolean = false
  private def privateMethod2(): Boolean = true
  override def publicMethod(): Boolean = true
}
'''

    void 'ALL_METHODS returns inherited and inaccessible methods but not overridden ones'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        when:
        def all = element.getEnclosedElements(ElementQuery.ALL_METHODS)

        then: 'a private method of the supertype is still reported, as in Java'
        all*.name.sort() == ['itfeMethod', 'otherSuper', 'privateMethod', 'privateMethod2', 'publicMethod']

        and: 'the overriding declaration is the one that wins'
        all.find { it.name == 'publicMethod' }.declaringType.simpleName == 'Test'
        all.find { it.name == 'otherSuper' }.declaringType.simpleName == 'SuperType'
        all.find { it.name == 'itfeMethod' }.declaringType.simpleName == 'SomeInt'

        and: 'property accessors are properties, not methods'
        !all*.name.contains('s1')
        !all*.name.contains('t1')
    }

    void 'onlyDeclared stops at the class itself'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        expect:
        element.getEnclosedElements(ElementQuery.of(MethodElement).onlyDeclared())*.name.sort() ==
                ['privateMethod2', 'publicMethod']
    }

    void 'onlyAccessible excludes what would need reflection'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        expect: 'Java also reports packagePrivateMethod and staticMethod here; Scala has neither'
        element.getEnclosedElements(ElementQuery.of(MethodElement).onlyAccessible())*.name.sort() ==
                ['itfeMethod', 'otherSuper', 'publicMethod']
    }

    void 'includeOverriddenMethods reports every declaration'() {
        given:
        def element = buildClassElement('elementquery.Child', '''
package elementquery

class Parent {
  def shared(): String = "p"
}

class Child extends Parent {
  override def shared(): String = "c"
}
''')

        when:
        def overriding = element.getEnclosedElements(ElementQuery.ALL_METHODS)
        def all = element.getEnclosedElements(ElementQuery.ALL_METHODS.includeOverriddenMethods())

        then: 'without the flag the overridden declaration is hidden'
        overriding*.declaringType*.simpleName == ['Child']

        and: 'with it, both declarations are returned'
        all*.declaringType*.simpleName.sort() == ['Child', 'Parent']
    }

    void 'a modifier filter selects on the modifiers'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        expect: 'Scala has no static members on a class -- they live on the companion object'
        element.getEnclosedElements(ElementQuery.ALL_METHODS.modifiers { it.contains(ElementModifier.STATIC) }).isEmpty()

        and:
        element.getEnclosedElements(ElementQuery.ALL_METHODS.modifiers { it.contains(ElementModifier.PRIVATE) })*.name.sort() ==
                ['privateMethod', 'privateMethod2']
    }

    void 'ALL_FIELDS returns the backing fields of the hierarchy'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        expect:
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)*.name.sort() == ['s1', 's2', 't1', 't2']
    }

    void 'no Scala field is accessible'() {
        given:
        def element = buildClassElement('elementquery.Test', HIERARCHY)

        expect: 'Java reports its public fields here; a Scala val or var is a private field'
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyAccessible()).isEmpty()
    }

    void 'CONSTRUCTORS is declared-only and the plain query is not'() {
        given:
        def element = buildClassElement('elementquery.Test', '''
package elementquery

class SuperType(s: String) {
  def this() = this("")
}

class Test(i: Int) extends SuperType("x") {
  def this() = this(0)
}
''')

        when:
        def declared = element.getEnclosedElements(ElementQuery.CONSTRUCTORS)

        then: 'ElementQuery.CONSTRUCTORS is of(ConstructorElement).onlyDeclared()'
        declared.size() == 2
        declared*.declaringType*.simpleName.toUnique() == ['Test']

        when:
        def all = element.getEnclosedElements(ElementQuery.of(ConstructorElement))

        then: 'so the query without that flag asks for the superclass constructors too'
        all.size() == 4
        all*.declaringType*.simpleName.toSet() == ['Test', 'SuperType'] as Set
    }

    void 'enum constants are fields only when asked for'() {
        given:
        def element = buildClassElement('elementquery.Suit', '''
package elementquery

enum Suit(val label: String):
  case Hearts extends Suit("h")
  case Spades extends Suit("s")
''')

        expect:
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)*.name == ['label']
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.includeEnumConstants())*.name.sort() ==
                ['Hearts', 'Spades', 'label']
    }
}
