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

import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code FactoryBeanFieldSpec}.
 *
 * <p>A {@code @Factory} can produce beans from fields as well as methods, and the field's
 * <em>finality</em> decides the scope: final is a singleton, non-final a prototype. Every
 * factory case covered here so far produces from a method, where that question does not
 * arise.</p>
 *
 * <p>Scala has no public fields to annotate. A {@code val} is a private field plus an accessor,
 * so whether a factory field bean is reachable at all depends on what the plugin decides to
 * expose. The finality rule then lands on the one distinction Scala draws in exactly that place:
 * {@code val} is final and {@code var} is not, so the Java rule reads across as val-is-singleton
 * and var-is-prototype -- if the model reports finality faithfully.</p>
 */
class ScalaFactoryFieldParitySpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package factoryfield

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Primary
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Named
import jakarta.inject.Singleton

class Foo(val name: String)

@Factory
class FooFactory:
  @Singleton
  @Bean
  @Primary
  val one: Foo = Foo("one")

  @Bean
  @Named("two")
  val two: Foo = Foo("two")

  @Bean
  @Named("three")
  var three: Foo = Foo("three")

  @Prototype
  @Bean
  @Named("four")
  def four: Foo = Foo("four")
'''

    void "produces beans from Scala factory fields"() {
        when:
        def context = buildContext(SOURCE, [:], true)
        def fooType = context.classLoader.loadClass('factoryfield.Foo')

        then: 'a val with no qualifier is reachable as the primary'
        context.getBean(fooType).name() == 'one'

        and: 'and each qualified field produces its own bean'
        context.getBean(fooType, Qualifiers.byName('two')).name() == 'two'
        context.getBean(fooType, Qualifiers.byName('three')).name() == 'three'

        cleanup:
        context?.close()
    }

    void "produces one instance per factory field, however it is declared"() {
        when:
        def context = buildContext(SOURCE, [:], true)
        def fooType = context.classLoader.loadClass('factoryfield.Foo')

        then: '''both give back the same instance. The Java spec\u0027s source comments say a
               non-final field is a prototype, but its assertions check identity for the final
               and non-final field alike, and core infers no scope from finality: a field bean
               is a read of one value, so there is nothing for a second resolution to produce'''
        context.getBean(fooType, Qualifiers.byName('two'))
            .is(context.getBean(fooType, Qualifiers.byName('two')))
        context.getBean(fooType, Qualifiers.byName('three'))
            .is(context.getBean(fooType, Qualifiers.byName('three')))

        and: '''which is what separates a field from a method. `def four` is re-invoked, so
               @Prototype there does produce a new instance -- the same annotation on the same
               factory, told apart only by what it is attached to'''
        !context.getBean(fooType, Qualifiers.byName('four'))
            .is(context.getBean(fooType, Qualifiers.byName('four')))

        cleanup:
        context?.close()
    }
}
