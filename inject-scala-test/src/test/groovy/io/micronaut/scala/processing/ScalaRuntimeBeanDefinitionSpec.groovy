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

class ScalaRuntimeBeanDefinitionSpec extends AbstractScalaTypeElementSpec {

    void "supports Scala dynamic bean definition registration"() {
        given:
        def context = buildContext('''
package registerref

import io.micronaut.context.BeanDefinitionRegistry
import io.micronaut.context.RuntimeBeanDefinition
import io.micronaut.context.annotation.Context
import io.micronaut.core.annotation.Order
import io.micronaut.inject.qualifiers.PrimaryQualifier
import io.micronaut.inject.qualifiers.Qualifiers
import jakarta.inject.Named
import jakarta.inject.Singleton

@Singleton
class Foo(@Named("test2") val bazz: Bazz, val bar: Bar, @Named("another") val another: Bar)

@Context
@Order(-10)
class RegistrarA(registry: BeanDefinitionRegistry):
  RegistrarA.executed = true
  if !RegistrarB.executed then
    throw IllegalStateException("RegistrarB should have been executed first")
  if RegistrarC.executed then
    throw IllegalStateException("RegistrarC should not have been executed yet")
  registry.registerBeanDefinition(
    RuntimeBeanDefinition.builder(
      classOf[Bar],
      () => Bar("primary")
    ).qualifier(PrimaryQualifier.instance()).build()
  )

object RegistrarA:
  var executed: Boolean = false

@Context
@Order(-15)
class RegistrarB(registry: BeanDefinitionRegistry):
  RegistrarB.executed = true
  if RegistrarC.executed then
    throw IllegalStateException("RegistrarC should not have been executed yet")
  if RegistrarA.executed then
    throw IllegalStateException("RegistrarA should not have been executed yet")
  registry.registerBeanDefinition(
    RuntimeBeanDefinition.builder(
      classOf[Bar],
      () => Bar("another")
    ).qualifier(Qualifiers.byName("another")).build()
  )

object RegistrarB:
  var executed: Boolean = false

@Context
class RegistrarC(registry: BeanDefinitionRegistry):
  if !RegistrarB.executed then
    throw IllegalStateException("RegistrarB should have been executed first")
  if !RegistrarA.executed then
    throw IllegalStateException("RegistrarA should have been executed first")
  RegistrarC.executed = true
  registry.registerBeanDefinition(RuntimeBeanDefinition.of(Stuff()))
  registry.registerBeanDefinition(
    RuntimeBeanDefinition.builder(
      classOf[Bazz],
      () => BazzImpl(1)
    ).named("test").build()
  )
  registry.registerBeanDefinition(
    RuntimeBeanDefinition.builder(
      classOf[Bazz],
      () => BazzImpl(2)
    ).named("test2").build()
  )

object RegistrarC:
  var executed: Boolean = false

case class Bar(name: String)

class Stuff

trait Bazz

case class BazzImpl(num: Int) extends Bazz
''')
        def foo = getBean(context, 'registerref.Foo')

        expect:
        foo.bar() != null
        foo.bazz() != null
        foo.bazz().num() == 2
        foo.bar().name() == 'primary'
        foo.another().name() == 'another'

        cleanup:
        context?.close()
    }
}
