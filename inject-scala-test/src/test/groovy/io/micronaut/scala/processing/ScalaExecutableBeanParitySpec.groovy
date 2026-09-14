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
 * P2 parity, ported from {@code inject-java}'s {@code ExecutableBeanSpec},
 * {@code ExecutableSpec} and {@code ExecutableSuperclassSpec}.
 *
 * <p>{@code @Executable} on a class makes its public methods executable without naming any of
 * them, so what gets written is decided by which members the model reports as public and
 * declared. Coverage here puts the annotation on individual methods, where that decision never
 * arises.</p>
 *
 * <p>The Scala question is what "public method" means for a class whose fields are all private
 * accessors and whose supertypes are usually traits. A {@code val} contributes an accessor, a
 * {@code case class} contributes several synthetic members, and a superclass contributes its own
 * -- each of which would be written as an executable method if the walk did not distinguish
 * them.</p>
 */
class ScalaExecutableBeanParitySpec extends AbstractScalaTypeElementSpec {

    void "makes the public methods of an Executable class executable"() {
        when:
        def definition = buildBeanDefinition('executablebean.Service', '''
package executablebean

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Executable
@Singleton
class Service:
  def visible(name: String): String = name
  def alsoVisible(): Int = 1
  private def hidden(): String = "no"
''')

        then: 'both public methods, and not the private one'
        definition.executableMethods*.methodName.toSet() == ['visible', 'alsoVisible'] as Set

        and: 'with their signatures intact'
        definition.findMethod('visible', String).present
        definition.getRequiredMethod('alsoVisible').returnType.type == Integer.TYPE
    }

    void "makes a method inherited from a superclass executable"() {
        when:
        def definition = buildBeanDefinition('executablebean.Child', '''
package executablebean

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

abstract class Parent:
  def fromParent(): String = "parent"

@Executable
@Singleton
class Child extends Parent:
  def fromChild(): String = "child"
''')

        then: 'the inherited method is written alongside the declared one, each once'
        definition.executableMethods*.methodName.toSet() == ['fromParent', 'fromChild'] as Set
        definition.executableMethods.size() == 2
    }

    void "writes a val's accessor as an executable method, as a getter would be"() {
        when: 'the class has a val, which is a private field and a public accessor'
        def definition = buildBeanDefinition('executablebean.Holder', '''
package executablebean

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Executable
@Singleton
class Holder(val name: String = "x"):
  def act(): String = name
''')

        then: '''the accessor is a public method, so it is executable -- Java writes a public
                 `getName()` for the same reason. Being a property as well does not exclude it,
                 and a first draft asserting the opposite was reading Scala's `val` as a field
                 rather than as the accessor it compiles to'''
        definition.executableMethods*.methodName.contains('act')
        definition.executableMethods*.methodName.contains('name')
    }
}
