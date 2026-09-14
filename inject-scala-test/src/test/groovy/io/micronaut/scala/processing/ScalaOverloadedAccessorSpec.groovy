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
 * Property accessors were collected into a map keyed by bare method name, so an overload
 * displaced the accessor it shared a name with and the property took its type, modifiers
 * and annotations from the overload instead.
 */
class ScalaOverloadedAccessorSpec extends AbstractScalaTypeElementSpec {

    void 'an overload does not displace the property accessor'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
case class Holder(value: String) {
  def value(index: Int): String = value.substring(index)
}
''')

        when:
        def property = element.getBeanProperties().find { it.getName() == 'value' }

        then: 'the property is the no-argument accessor, not the one-argument overload'
        property != null
        property.getType().getName() == 'java.lang.String'
        property.getReadMethod().isPresent()
        property.getReadMethod().get().getParameters().length == 0
    }

    void 'an overloaded setter does not displace the write accessor'() {
        given:
        def element = buildClassElement('test.Mutable', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Mutable {
  var value: String = ""
  def value_=(first: String, second: String): Unit = { value = first + second }
}
''')

        when:
        def property = element.getBeanProperties().find { it.getName() == 'value' }

        then:
        property != null
        property.getWriteMethod().isPresent()
        property.getWriteMethod().get().getParameters().length == 1
        property.getWriteMethod().get().getParameters()[0].getType().getName() == 'java.lang.String'
    }

    void 'the overload is still reported as a method'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

case class Holder(value: String) {
  def value(index: Int): String = value.substring(index)
}
''')

        when:
        def overloads = element.getEnclosedElements(io.micronaut.inject.ast.ElementQuery.ALL_METHODS.onlyDeclared())
                .findAll { it.getName() == 'value' }

        then: 'narrowing the accessor map must not lose the method itself'
        overloads.any { it.getParameters().length == 1 }
    }
}
