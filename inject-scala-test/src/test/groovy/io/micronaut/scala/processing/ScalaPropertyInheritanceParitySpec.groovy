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
 * Ported from the Groovy module's {@code ClassElementSpec} -- "interface bean properties",
 * "correct properties declaring class with inheritance", and "no package". The Groovy module
 * is a third non-javac frontend, so it exercises the same ground the Java module does but
 * against a compiler that models things differently.
 */
class ScalaPropertyInheritanceParitySpec extends AbstractScalaTypeElementSpec {

    void 'a trait exposes read-only bean properties'() {
        given:
        def element = buildClassElement('test.HealthResult', '''
package test

trait HealthResult {
  def getName(): String
  def getStatus(): Object
  def getDetails(): Object
}
''')

        when:
        def properties = element.getBeanProperties()

        then:
        properties*.name.toSet() == ['name', 'status', 'details'] as Set

        and: 'a getter with no setter is read-only'
        properties.every { it.isReadOnly() }
        properties.find { it.name == 'name' }.getType().getName() == 'java.lang.String'
    }

    void 'an inherited property keeps its declaring type and its annotations'() {
        given:
        def element = buildClassElement('test.Child', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.validation.constraints.NotNull

@Introspected
abstract class BaseDto {
  @NotNull var id: String = ""
  var version: Integer = null
}

@Introspected
class Child extends BaseDto {
  var name: String = ""
}
''')
        def properties = element.getBeanProperties().collectEntries { [it.name, it] }

        expect: 'the declared one'
        properties.name.getDeclaringType().getSimpleName() == 'Child'

        and: 'and the inherited ones, which must not be re-declared on the subclass'
        properties.id.getDeclaringType().getSimpleName() == 'BaseDto'
        properties.version.getDeclaringType().getSimpleName() == 'BaseDto'

        and: 'a constraint written on the superclass property survives the walk down'
        properties.id.getAnnotationMetadata().getAnnotationNames()
                .contains('jakarta.validation.constraints.NotNull$List')
    }

    void 'a type in the default package has an unnamed package'() {
        given:
        def element = buildClassElement('NoPackage', '''
class NoPackage {
  def name(): String = "x"
}
''')

        expect: 'core rejects beans here, and reads isUnnamed() to decide'
        element.getPackageName() == ''
        element.getPackage().isUnnamed()
    }
}
