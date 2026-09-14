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

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ScalaPoCSpec extends AbstractScalaTypeElementSpec {

    void "builds bean definition for singleton with constructor injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

@Singleton
class Engine

@Singleton
class Car(val engine: Engine)
''')

        then:
        context.containsBean(context.classLoader.loadClass('example.Engine'))
        context.containsBean(context.classLoader.loadClass('example.Car'))
        getBean(context, 'example.Car').engine != null

        cleanup:
        context?.close()
    }

    void "builds bean introspection for case class"() {
        given:
        def source = '''
package example

import io.micronaut.core.annotation.Introspected

@Introspected
case class Person(name: String, age: Int)
'''

        when:
        def element = buildClassElement('example.Person', source)
        def introspection = buildBeanIntrospection('example.Person', source)

        then:
        element.hasStereotype('io.micronaut.core.annotation.Introspected')
        element.getAnnotation('io.micronaut.core.annotation.Introspected') != null
        element.getBeanProperties()*.name as Set == ['name', 'age'] as Set
        introspection != null
        introspection.beanType.name == 'example.Person'
        introspection.getRequiredProperty('name', String)
        introspection.getRequiredProperty('age', Integer.TYPE)
    }

    void "builds bean introspection for mutable var properties"() {
        when:
        def introspection = buildBeanIntrospection('example.AppConfig', '''
package example

import io.micronaut.core.annotation.Introspected

@Introspected
class AppConfig:
  var port: Int = 0
''')
        def bean = introspection.instantiate()
        def property = introspection.getRequiredProperty('port', Integer.TYPE)

        then:
        property.get(bean) == 0

        when:
        property.set(bean, 8080)

        then:
        property.get(bean) == 8080
    }

    void "loads generated bean definition"() {
        when:
        def definition = buildBeanDefinition('example.Engine', '''
package example

import jakarta.inject.Singleton

@Singleton
class Engine
''')

        then:
        definition != null
        definition.beanType.name == 'example.Engine'
    }

    void "type element visitor sees class method and property metadata"() {
        when:
        def element = buildClassElement('example.Vehicle', '''
package example

import jakarta.inject.Singleton

@Singleton
class Vehicle(val name: String, var mileage: Int):
  def description: String = name
''')

        then:
        element != null
        element.name == 'example.Vehicle'
        element.hasAnnotation('jakarta.inject.Singleton')
        element.hasStereotype('jakarta.inject.Scope')
        element.getBeanProperties()*.name as Set == ['name', 'mileage'] as Set
        element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'description' })).present
        element.getPrimaryConstructor().get().parameters*.name == ['name', 'mileage']
    }

    void "visitor context reports Scala language"() {
        expect:
        buildClassElement('example.Engine', '''
package example

class Engine
''') { element ->
            assert element.name == 'example.Engine'
        }
    }
}
