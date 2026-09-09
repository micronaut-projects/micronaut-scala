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
 * P2 parity, ported from {@code inject-java}'s {@code InheritedExecutableSpec}.
 *
 * <p>Overriding a generic method makes the compiler emit a bridge: the subclass gets both
 * {@code save(String)} and a synthetic {@code save(Object)} that forwards to it. If both reach
 * the definition the method is written twice, and a caller resolving it by argument type can
 * get the bridge -- which is exactly the sort of duplicate the hierarchy walk on this branch
 * had to stop producing when it moved to level order.</p>
 *
 * <p>Counting is the whole point here. Executable coverage so far asks whether a method is
 * present, and a duplicate is present too, so nothing established that the count is right.
 * The overload is in the fixture to make sure de-duplication distinguishes an overload from a
 * bridge, which differ only in what their parameter types erase to.</p>
 */
class ScalaInheritedExecutableParitySpec extends AbstractScalaTypeElementSpec {

    void "writes an overridden generic method once, and its overload beside it"() {
        when:
        def definition = buildBeanDefinition('inheritedexec.StatusController', '''
package inheritedexec

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

abstract class GenericController[T]:
  def path: String

  @Executable
  def save(entity: T): String = "parent"

  @Executable
  def save(): String = "parent"

@Executable
@Singleton
class StatusController extends GenericController[String]:
  override def path: String = "/statuses"

  override def save(entity: String): String = "child"
''')
        def executables = definition.executableMethods

        then: 'the abstract member and both arities, and nothing else'
        executables.any { it.methodName == 'path' }
        executables.any { it.methodName == 'save' && it.argumentTypes as List == [String] }
        executables.any { it.methodName == 'save' && it.argumentTypes.length == 0 }

        and: 'the bridge the compiler emits alongside the override is not a fourth entry'
        executables.findAll { it.methodName == 'save' }.size() == 2
        executables.size() == 3
    }

    void "writes nothing for an abstract generic base that is not a bean"() {
        when: 'the base carries an executable method but no bean-defining annotation'
        def definition = buildBeanDefinition('inheritedexec.GenericController', '''
package inheritedexec

import io.micronaut.context.annotation.Executable

abstract class GenericController[T]:
  def path: String

  @Executable
  def save(entity: T): String = "parent"
''')

        then:
        definition == null
    }

    void "writes an overridden method once when it is inherited through a trait"() {
        when: 'the generic member arrives as a trait method rather than a class one'
        def definition = buildBeanDefinition('inheritedexec.StatusController', '''
package inheritedexec

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

trait GenericOperations[T]:
  @Executable
  def save(entity: T): String

@Executable
@Singleton
class StatusController extends GenericOperations[String]:
  override def save(entity: String): String = "child"
''')
        def executables = definition.executableMethods

        then: 'a trait is an interface, so the bridge lands on the class implementing it'
        executables.findAll { it.methodName == 'save' }.size() == 1
        executables.find { it.methodName == 'save' }.argumentTypes as List == [String]
    }
}
