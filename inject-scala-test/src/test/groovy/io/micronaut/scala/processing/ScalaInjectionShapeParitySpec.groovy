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
 * P1 parity, ported from {@code inject-java}'s {@code SetterArrayInjectionSpec},
 * {@code BuilderStyleInjectionSpec}, {@code SetterWithQualifierSpec},
 * {@code FieldInheritanceInjectionSpec} and {@code OptionalPropertySpec}.
 *
 * <p>Injection through a setter has shapes that differ only in the setter's signature, and each
 * is decided when the definition is written rather than at runtime: an array parameter, a
 * builder-style setter that returns something instead of {@code void}, a qualifier on the
 * parameter, and a setter inherited from a supertype. Constructor injection is covered here
 * thoroughly; the setter forms are what these add.</p>
 *
 * <p>Scala reaches most of them differently. A builder-style setter returning {@code this.type}
 * is ordinary rather than a curiosity, an inherited setter usually arrives from a trait, and an
 * absent optional property is the {@code Option} question this branch has already had to answer
 * twice -- once for a constructor parameter and once for a trait accessor.</p>
 */
class ScalaInjectionShapeParitySpec extends AbstractScalaTypeElementSpec {

    void "injects an array through a setter"() {
        when:
        def context = buildContext('''
package injectionshape

import jakarta.inject.Inject
import jakarta.inject.Singleton

trait Part

@Singleton
class Wheel extends Part

@Singleton
class Axle extends Part

@Singleton
class Vehicle:
  var parts: Array[Part] = Array.empty

  @Inject
  def setParts(parts: Array[Part]): Unit = this.parts = parts
''', [:], true)
        def vehicle = getBean(context, 'injectionshape.Vehicle')

        then: 'every bean of the element type is collected into the array'
        vehicle.parts().length == 2
        vehicle.parts().collect { it.getClass().simpleName }.toSet() == ['Wheel', 'Axle'] as Set

        cleanup:
        context?.close()
    }

    void "injects through a builder-style setter that does not return void"() {
        when: 'the setter returns the bean so calls could chain'
        def context = buildContext('''
package injectionshape

import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class Engine:
  def start(): String = "running"

@Singleton
class Vehicle:
  var engine: Engine = null

  @Inject
  def setEngine(engine: Engine): Vehicle =
    this.engine = engine
    this
''', [:], true)
        def vehicle = getBean(context, 'injectionshape.Vehicle')

        then: 'a non-void return does not stop it being an injection point'
        vehicle.engine() != null
        vehicle.engine().start() == 'running'

        cleanup:
        context?.close()
    }

    void "honours a qualifier written on a setter parameter"() {
        when:
        def context = buildContext('''
package injectionshape

import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
@Named("v6")
class V6 extends Engine:
  override def name(): String = "v6"

@Singleton
@Named("v8")
class V8 extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle:
  var engine: Engine = null

  @Inject
  def setEngine(@Named("v8") engine: Engine): Unit = this.engine = engine
''', [:], true)
        def vehicle = getBean(context, 'injectionshape.Vehicle')

        then: 'the qualifier picks one of two candidates rather than failing as ambiguous'
        vehicle.engine().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "injects a setter a class inherits from a trait"() {
        when: 'the injection point is declared on the trait, not on the bean'
        def context = buildContext('''
package injectionshape

import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class Engine:
  def start(): String = "running"

trait HasEngine:
  var engine: Engine = null

  @Inject
  def setEngine(engine: Engine): Unit = this.engine = engine

@Singleton
class Vehicle extends HasEngine
''', [:], true)
        def vehicle = getBean(context, 'injectionshape.Vehicle')

        then:
        vehicle.engine() != null

        cleanup:
        context?.close()
    }

    void "injects an absent optional property as None"() {
        when: 'the property is not set, and the injection point is an Option'
        def context = buildContext('''
package injectionshape

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Holder(@Value("${missing.property}") val maybe: Option[String])
''', [:], true)
        def holder = getBean(context, 'injectionshape.Holder')

        then: 'the bean is built with None rather than failing to resolve'
        holder.maybe().isEmpty()

        when: 'and the same point with the property present'
        def present = buildContext('''
package injectionshape

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Holder(@Value("${missing.property}") val maybe: Option[String])
''', ['missing.property': 'here'], true)

        then:
        getBean(present, 'injectionshape.Holder').maybe().get() == 'here'

        cleanup:
        context?.close()
        present?.close()
    }
}
