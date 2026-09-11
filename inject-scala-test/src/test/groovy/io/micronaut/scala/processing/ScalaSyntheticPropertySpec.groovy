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
 * {@code getSyntheticBeanProperties()} means the properties whose accessors the compiler
 * created rather than the author. Core walks exactly that set for an ordinary bean, so
 * over-reporting there would make hand-written accessors injection candidates. And a
 * Scala {@code var} is only usable for setter injection if its generated {@code x_=}
 * accessor is recognised as the property's write method -- if it were not, every
 * extracted property would be read-only.
 */
class ScalaSyntheticPropertySpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package probe

import jakarta.inject.Singleton

@Singleton
class Holder(var fromConstructor: String) {
  private var backing: String = ""
  def getWritten(): String = backing
  def setWritten(value: String): Unit = { backing = value }
}
'''

    void 'only compiler-created accessors are synthetic properties'() {
        given:
        def element = buildClassElement('probe.Holder', SOURCE)

        expect: 'the var accessor is generated; the JavaBean pair is written by hand'
        element.getSyntheticBeanProperties()*.name == ['fromConstructor']
        element.getBeanProperties()*.name.toSet() == ['fromConstructor', 'written'] as Set
    }

    void 'the generated setter accessor is the property write method'() {
        given:
        def element = buildClassElement('probe.Holder', SOURCE)

        when:
        def property = element.getBeanProperties().find { it.name == 'fromConstructor' }

        then: 'dotty gives the x_= symbol the flags the extractor matches on'
        !property.isReadOnly()
        property.getWriteMethod().get().name == 'fromConstructor_$eq'
        property.getReadMethod().get().name == 'fromConstructor'
    }

    void 'an annotated hand-written setter is one injection point, not two'() {
        given:
        def definition = buildBeanDefinition('probe.Holder', '''
package probe

import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class Other

@Singleton
class Holder {
  private var backing: Other = null
  def getWritten(): Other = backing
  @Inject def setWritten(value: Other): Unit = { backing = value }
}
''')

        expect: 'Core visits synthetic properties and members separately'
        definition.getInjectedMethods()*.name == ['setWritten']
    }
}
