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
 * P1 parity, ported from {@code inject-java-test}'s {@code BeanIntrospectionGenericsSpec}.
 *
 * <p>An introspection records a property's type arguments so a reader can tell {@code List[String]}
 * from {@code List[Int]} without reflection. Introspection coverage here asserts property names
 * and values; the arguments are what serialization and validation read, and losing them leaves
 * every collection property looking like a collection of {@code Object}.</p>
 *
 * <p>Scala reaches them through its own generic model, and through the erasure rules this branch
 * has had to correct twice -- once for a hierarchy walk that widened an override to its bound,
 * once for a classpath type whose arguments were dropped when its declaration was resolved.</p>
 */
class ScalaIntrospectionGenericsParitySpec extends AbstractScalaTypeElementSpec {

    void "records the type arguments of a generic property"() {
        when:
        def introspection = buildBeanIntrospection('introgenerics.Holder', '''
package introgenerics

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  var names: java.util.List[String] = null
  var counts: java.util.Map[String, java.lang.Integer] = null
''')

        then:
        introspection.getRequiredProperty('names', List).asArgument().typeParameters*.type == [String]

        and: 'and both arguments of a two-parameter type, in order'
        introspection.getRequiredProperty('counts', Map).asArgument().typeParameters*.type ==
            [String, Integer]
    }

    void "records the type arguments a subclass fixes on its generic supertype"() {
        when: 'the property type is only concrete once the supertype is parameterised'
        def introspection = buildBeanIntrospection('introgenerics.StringHolder', '''
package introgenerics

import io.micronaut.core.annotation.Introspected

abstract class Holder[T]:
  var value: T = null.asInstanceOf[T]

@Introspected
class StringHolder extends Holder[String]
''')

        then: 'the inherited property reports the argument the subclass fixed, not its erasure'
        introspection.getRequiredProperty('value', String).type == String
    }

    void "records a nested type argument"() {
        when:
        def introspection = buildBeanIntrospection('introgenerics.Holder', '''
package introgenerics

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  var grouped: java.util.Map[String, java.util.List[java.lang.Integer]] = null
''')
        def argument = introspection.getRequiredProperty('grouped', Map).asArgument()

        then: 'the inner argument survives one level down, not only the outer pair'
        argument.typeParameters*.type == [String, List]
        argument.typeParameters[1].typeParameters*.type == [Integer]
    }
}
