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
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Ported from the Java module's {@code ClassElementSpec} "test recursive generic type
 * parameter". Modelling a self-referential bound recursed without end and killed the
 * compiler with a {@code StackOverflowError} -- not on anything exotic, but on
 * {@code class Sorted[T <: Ordered[T]]}.
 *
 * <p>There is a depth guard on type parameters. Two paths reset the counts it keeps before
 * recursing: a wildcard's bounds, and the walk into a type's parents. A self-referential
 * bound reaches its own type parameter through both.</p>
 */
class ScalaRecursiveGenericsSpec extends AbstractScalaTypeElementSpec {

    private static Map<String, String> argumentNames(element) {
        element?.getTypeArguments()?.collectEntries { name, argument -> [name, argument.getName()] } ?: [:]
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a bound that refers to its own type parameter terminates'() {
        given:
        def element = buildClassElement('test.Direct', '''
package test

final class Direct[T <: java.lang.Comparable[T]]
''')

        expect: 'two levels are modelled, as in the Java module'
        argumentNames(element) == [T: 'java.lang.Comparable']
        argumentNames(element.getTypeArguments()['T']) == [T: 'java.lang.Comparable']
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a self-referential bound reached through a parent terminates'() {
        given: 'Ordered[T] has Comparable[T] as a parent, which mentions T again'
        def element = buildClassElement('test.SelfRef', '''
package test

final class SelfRef[T <: Ordered[T]] {
  def item(): T = null.asInstanceOf[T]
}
''')

        expect:
        argumentNames(element) == [T: 'scala.math.Ordered']

        and: 'and the type variable still erases to its bound where it is used'
        element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'item' }
                .getReturnType()
                .getName() == 'scala.math.Ordered'
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a self-referential bound reached through a wildcard terminates'() {
        given:
        def element = buildClassElement('test.TrackedSortedSet', '''
package test

final class TrackedSortedSet[T <: java.lang.Comparable[? >: T]]
''')

        expect:
        argumentNames(element) == [T: 'java.lang.Comparable']

        and: 'a lower-bounded wildcard is reported as its upper bound, which is Object'
        argumentNames(element.getTypeArguments()['T']) == [T: 'java.lang.Object']
    }
}
