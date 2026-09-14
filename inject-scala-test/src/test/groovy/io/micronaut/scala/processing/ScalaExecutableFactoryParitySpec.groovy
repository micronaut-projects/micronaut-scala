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
 * P2 parity, ported from {@code inject-java}'s {@code ExecutableFactoryMethodSpec}.
 *
 * <p>{@code @Executable} on a {@code @Factory} method makes the methods of the <em>returned</em>
 * type executable, so what gets written is decided by walking a hierarchy the factory itself
 * never mentions. Everything else here queries a class that carries the annotation directly;
 * this is the one path where the model has to reach through a return type.</p>
 *
 * <p>Both cases are shapes Scala reaches sooner than Java does. A concrete trait method is the
 * normal way to share an implementation, and it has to be recognised as an inherited executable
 * rather than skipped as synthetic. A trait diamond with covariant overrides is what any
 * client-style API looks like, and the model must report the most specific override — with its
 * type arguments intact — rather than the erased declaration it first meets on the way up.</p>
 */
class ScalaExecutableFactoryParitySpec extends AbstractScalaTypeElementSpec {

    void "makes a concrete trait method executable on a factory-produced bean"() {
        when:
        def definition = buildGeneratedBeanDefinition('executablefactory', '$MyFactory$MyClass0$Definition', '''
package executablefactory

import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

trait SomeInterface:
  def goDog(): String
  def go(): String = "go"

@Factory
class MyFactory:
  @Singleton
  @Executable
  def myClass(): MyClass = new MyClass()

class MyClass extends SomeInterface:
  override def goDog(): String = "go"
''')

        then:
        noExceptionThrown()
        definition != null

        when: 'the produced type is instantiated directly, so only the written metadata is in play'
        def instance = definition.class.classLoader.loadClass('executablefactory.MyClass')
            .getDeclaredConstructor().newInstance()

        then: 'the inherited trait body is executable alongside the override'
        definition.findMethod('go').present
        definition.findMethod('go').get().invoke(instance) == 'go'
        definition.findMethod('goDog').get().invoke(instance) == 'go'
    }

    void "reports the most specific override when traits inherit through a diamond"() {
        when: 'retrieve and stream are each narrowed twice, once on each side of the diamond'
        def definition = buildGeneratedBeanDefinition('executablefactory', '$MyFactory$MyClient0$Definition', '''
package executablefactory

import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.util.Collection
import java.util.List

@Factory
class MyFactory:
  @Singleton
  @Executable
  def myClient(): MyClient = null

trait BaseClient:
  def retrieve(): Collection[?]

trait StreamingClient extends BaseClient:
  def stream(): Collection[Array[Byte]]

trait ListClient extends BaseClient:
  override def retrieve(): List[?]

trait StreamingListClient extends StreamingClient, ListClient:
  override def stream(): List[Array[Byte]]

trait MyClient extends StreamingListClient:
  def blocking(): Array[Byte]
''')

        then:
        noExceptionThrown()
        definition != null

        when:
        def retrieve = definition.getRequiredMethod('retrieve')
        def stream = definition.getRequiredMethod('stream')
        def blocking = definition.getRequiredMethod('blocking')

        then: 'the narrowed declaration wins over the one first met walking up'
        retrieve.returnType.type == List
        stream.returnType.type == List

        and: 'and its type arguments survive the walk'
        retrieve.returnType.typeParameters.length == 1
        retrieve.returnType.typeParameters[0].type == Object
        stream.returnType.typeParameters.length == 1
        stream.returnType.typeParameters[0].type == byte[]

        and: 'a method declared only on the leaf is unaffected'
        blocking.returnType.type == byte[]
    }
}
