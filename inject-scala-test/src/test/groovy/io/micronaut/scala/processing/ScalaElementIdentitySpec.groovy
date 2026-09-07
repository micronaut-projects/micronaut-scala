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
 * Element equality drives Micronaut's element caching, so two elements that are equal are
 * interchangeable. Placeholders were keyed on their *erasure*, which made every type
 * variable of a class equal to every other and to a plain {@code java.lang.Object}.
 */
class ScalaElementIdentitySpec extends AbstractScalaTypeElementSpec {

    void 'distinct type variables are distinct elements'() {
        given:
        def element = buildClassElement('probe.Repo', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Repo[T, U] {
  def find(): T = null.asInstanceOf[T]
  def other(): U = null.asInstanceOf[U]
}
''')

        when:
        def args = element.getTypeArguments()

        then: 'both erase to Object, but they are different type variables'
        args['T'] != args['U']
        args['T'].hashCode() != args['U'].hashCode()

        and: 'and each still equals itself'
        args['T'] == element.getTypeArguments()['T']
    }

    void 'a type variable is not equal to its erasure'() {
        given:
        def element = buildClassElement('probe.Repo', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Repo[T] {
  def find(): T = null.asInstanceOf[T]
  def raw(): Object = null
}
''')
        def declared = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())

        when:
        def variable = element.getTypeArguments()['T']
        def erasure = declared.find { it.name == 'raw' }.getReturnType()

        then:
        variable != erasure
    }

    void 'annotating a wildcard keeps it a wildcard'() {
        given:
        def element = buildClassElement('probe.Holder', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Holder {
  def values(): java.util.List[_ <: CharSequence] = null
}
''')
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'values' }
        def argument = method.getReturnType().getFirstTypeArgument().get()

        expect:
        argument instanceof WildcardElement

        when: 'copying is what annotating an element does'
        def copy = argument.withAnnotationMetadata(argument.getAnnotationMetadata())

        then: 'the copy must still satisfy the instanceof checks in the generics writers'
        copy instanceof WildcardElement
    }

    void 'an array of a wildcard is not a wildcard'() {
        given:
        def introspection = buildBeanIntrospection('probe.Test', '''
package probe

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(var starArray: Array[_])
''')

        expect: '`Array[_]` erases to Object[], so it must not be carried as a wildcard'
        introspection.getRequiredProperty('starArray', Object[].class).type == Object[].class
    }
}
