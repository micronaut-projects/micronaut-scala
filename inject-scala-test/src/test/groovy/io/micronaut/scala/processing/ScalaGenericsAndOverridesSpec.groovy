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
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * {@code withTypeArguments} and {@code overrides}/{@code hides} were stubs -- the first
 * returned the receiver unchanged, the other two returned false unconditionally. Both are
 * consulted by Micronaut wherever generics are resolved and wherever an inherited
 * injection point has to be recognised as the same member.
 */
class ScalaGenericsAndOverridesSpec extends AbstractScalaTypeElementSpec {

    void 'withTypeArguments resolves the type arguments'() {
        given:
        def element = buildClassElement('test.Repo', '''
package test

import jakarta.inject.Singleton

@Singleton
class Repo[T] {
  def find(): T = null.asInstanceOf[T]
}
''')
        def stringElement = buildClassElement('test.Holder', '''
package test

import jakarta.inject.Singleton

@Singleton
class Holder
''')

        when:
        def resolved = element.withTypeArguments(['T': stringElement])

        then: 'the copy reports the substitution, and is not the original'
        !resolved.is(element)
        resolved.getTypeArguments()['T'].name == 'test.Holder'

        and: 'the original is untouched'
        element.getTypeArguments()['T'].name != 'test.Holder'

        and: 'members survive the copy'
        resolved.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .any { it.name == 'find' }
    }

    void 'withArrayDimensions keeps the declared members'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import jakarta.inject.Singleton

@Singleton
class Target {
  def compute(): Int = 1
}
''')

        when:
        def array = element.withArrayDimensions(1)

        then:
        array.getArrayDimensions() == 1
        array.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .any { it.name == 'compute' }
    }

    private static final String INHERITANCE_SOURCE = '''
package test

import jakarta.inject.Singleton

class Parent {
  def compute(value: Int): Int = value
  def other(): Int = 0
}

@Singleton
class Child extends Parent {
  override def compute(value: Int): Int = value + 1
}
'''

    void 'an overriding method reports that it overrides the parent'() {
        given: 'the parent is read from its own element -- an overridden method is de-duplicated out of the child\'s inherited members'
        def child = buildClassElement('test.Child', INHERITANCE_SOURCE)
        def parent = buildClassElement('test.Parent', INHERITANCE_SOURCE)
        def declared = { it, name -> it.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).find { m -> m.name == name } }
        def childCompute = declared(child, 'compute')
        def parentCompute = declared(parent, 'compute')
        def parentOther = declared(parent, 'other')

        expect: 'the override is recognised'
        childCompute != null
        parentCompute != null
        childCompute.overrides(parentCompute)

        and: 'a method is not an override of itself'
        !childCompute.overrides(childCompute)

        and: 'a method with a different name is not overridden'
        parentOther != null
        !childCompute.overrides(parentOther)

        and: 'overriding is directional -- the parent does not override the child'
        !parentCompute.overrides(childCompute)
    }

    void 'a different parameter signature is not an override'() {
        given:
        def element = buildClassElement('test.Child', '''
package test

import jakarta.inject.Singleton

class Parent {
  def compute(value: Int): Int = value
}

@Singleton
class Child extends Parent {
  def compute(value: String): Int = value.length
}
''')
        def all = element.getEnclosedElements(ElementQuery.ALL_METHODS)
        def childCompute = all.find {
            it.name == 'compute' && it.declaringType.name == 'test.Child'
        }
        def parentCompute = all.find {
            it.name == 'compute' && it.declaringType.name == 'test.Parent'
        }

        expect:
        childCompute != null
        parentCompute != null
        !childCompute.overrides(parentCompute)
    }
}
