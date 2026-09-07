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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Coverage for Scala language constructs that a Scala user meets immediately but that the
 * Java, Groovy and Kotlin parity suites have no equivalent for.
 */
class ScalaLanguageFeatureSpec extends AbstractScalaTypeElementSpec {

    void "test classpath Java superclass is reported as a super type not an interface"() {
        given:
        ClassElement element = buildClassElement('test.Engine', '''
package test

import io.micronaut.scala.processing.fixtures.ExternalMachine

class Engine extends ExternalMachine
''')

        expect:
        element != null
        element.superType.isPresent()
        element.superType.get().name == 'io.micronaut.scala.processing.fixtures.ExternalMachine'
        element.interfaces.every { it.name != 'io.micronaut.scala.processing.fixtures.ExternalMachine' }
    }

    void "test classpath Java interface is reported as an interface"() {
        given:
        ClassElement element = buildClassElement('test.Task', '''
package test

class Task extends Runnable {
  override def run(): Unit = ()
}
''')

        expect:
        element != null
        element.interfaces.any { it.name == 'java.lang.Runnable' }
        !element.superType.isPresent() || element.superType.get().name != 'java.lang.Runnable'
    }
}
