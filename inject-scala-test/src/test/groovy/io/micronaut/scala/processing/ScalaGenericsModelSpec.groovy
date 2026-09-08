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
 * Ported from the Kotlin module's {@code ClassElementSpec} -- "generics model", "generics
 * model for wildcard", "generics model for placeholder" and "wildcard explicit bounds use
 * type argument variance". The generics writers branch on
 * {@code isGenericPlaceholder()} and {@code isWildcard()}, so a type argument answering
 * either wrongly changes what is written.
 */
class ScalaGenericsModelSpec extends AbstractScalaTypeElementSpec {

    private static final String LST = '''
package test

class Lst[E]
'''

    private argumentOf(String body, String method) {
        buildClassElement('test.Test', LST + body)
                .getEnclosedElement(ElementQuery.ALL_METHODS.named { it == method })
                .get()
    }

    void 'a nested concrete type argument is neither placeholder nor wildcard'() {
        given:
        def method = argumentOf('''
class Test {
  def method1(): Lst[Lst[Lst[String]]] = null
}
''', 'method1')

        when:
        def level1 = method.getGenericReturnType().getTypeArguments()['E']
        def level2 = level1.getTypeArguments()['E']
        def level3 = level2.getTypeArguments()['E']

        then:
        [level1, level2, level3].every { !it.isGenericPlaceholder() && !it.isWildcard() }

        and:
        level3.getName() == 'java.lang.String'

        and: 'and the erased view agrees'
        def erased = method.getReturnType().getTypeArguments()['E']
        !erased.isGenericPlaceholder()
        !erased.isWildcard()
    }

    void 'an unbounded wildcard argument is a wildcard'() {
        given:
        def method = argumentOf('''
class Test[T] {
  def method(): Lst[?] = null
}
''', 'method')

        expect:
        def argument = method.getGenericReturnType().getTypeArguments()['E']
        argument.isWildcard()
        !argument.isGenericPlaceholder()
        argument.getName() == 'java.lang.Object'

        and: 'the Java module answers false here too; only the Kotlin module calls it raw'
        !argument.isRawType()
    }

    void 'a type variable argument is a placeholder'() {
        given:
        def method = argumentOf('''
class Test[T] {
  def method(): Lst[T] = null
}
''', 'method')

        expect:
        def argument = method.getGenericReturnType().getTypeArguments()['E']
        argument.isGenericPlaceholder()
        !argument.isWildcard()
    }

    void 'an upper-bounded wildcard reports its bound'() {
        given:
        def method = argumentOf('''
class Test {
  def upper(): Lst[? <: CharSequence] = null
}
''', 'upper')

        expect:
        def argument = method.getGenericReturnType().getTypeArguments()['E']
        argument.isWildcard()
        argument.getName() == 'java.lang.CharSequence'
    }

    void 'a lower-bounded wildcard reports its upper bound'() {
        given:
        def method = argumentOf('''
class Test {
  def lower(): Lst[? >: String] = null
}
''', 'lower')

        expect: 'less detail than the Java and Kotlin modules give, but not a wrong JVM type'
        def argument = method.getGenericReturnType().getTypeArguments()['E']
        argument.isWildcard()
        argument.getName() == 'java.lang.Object'
    }
}
