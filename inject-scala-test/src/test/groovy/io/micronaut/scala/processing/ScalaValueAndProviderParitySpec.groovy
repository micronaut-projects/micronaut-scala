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
 * P1 parity, ported from {@code inject-java}'s {@code ValueParseSpec},
 * {@code PropertyAnnotationSpec}, {@code BeanProviderSpec},
 * {@code DisableErrorOnMissingBeanProviderSpec}, {@code FactoryFieldArraySpec} and
 * {@code FactoryWithValueSpec}.
 *
 * <p>These are the resolution behaviours that decide whether a bean can be built at all: a value
 * expression with a default, a property injected by name, a provider that defers resolution and
 * can report that nothing satisfies it, and a factory field producing several beans.</p>
 *
 * <p>Each is a place where Scala's own types change what "absent" means. A provider of a missing
 * bean has to be resolvable while being empty, and a defaulted value has to reach a constructor
 * parameter, which is where Scala puts almost everything.</p>
 */
class ScalaValueAndProviderParitySpec extends AbstractScalaTypeElementSpec {

    void "resolves a value expression with a default when the property is absent"() {
        when:
        def context = buildContext('''
package valueprovider

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Holder(
  @Value("${app.name:fallback}") val name: String,
  @Value("${app.port:8080}") val port: Int
)
''', [:], true)
        def holder = getBean(context, 'valueprovider.Holder')

        then: 'the default in the expression is used, and converted to the parameter type'
        holder.name() == 'fallback'
        holder.port() == 8080

        when: 'and a set property wins over the default'
        def set = buildContext('''
package valueprovider

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Holder(@Value("${app.name:fallback}") val name: String)
''', ['app.name': 'given'], true)

        then:
        getBean(set, 'valueprovider.Holder').name() == 'given'

        cleanup:
        context?.close()
        set?.close()
    }

    void "injects a property by name with @Property"() {
        when:
        def context = buildContext('''
package valueprovider

import io.micronaut.context.annotation.Property
import jakarta.inject.Singleton

@Singleton
class Holder(@Property(name = "app.name") val name: String)
''', ['app.name': 'by-name'], true)

        then:
        getBean(context, 'valueprovider.Holder').name() == 'by-name'

        cleanup:
        context?.close()
    }

    void "defers resolution through a BeanProvider and reports an absent bean"() {
        when: 'one provider has a bean to give and one does not'
        def context = buildContext('''
package valueprovider

import io.micronaut.context.BeanProvider
import jakarta.inject.Singleton

trait Present:
  def name(): String

@Singleton
class PresentImpl extends Present:
  override def name(): String = "here"

trait Absent

@Singleton
class Holder(val present: BeanProvider[Present], val absent: BeanProvider[Absent])
''', [:], true)
        def holder = getBean(context, 'valueprovider.Holder')

        then: 'the provider resolves lazily when asked'
        holder.present().isPresent()
        holder.present().get().name() == 'here'

        and: 'and one with nothing to give is still injectable, and says so'
        !holder.absent().isPresent()

        cleanup:
        context?.close()
    }

    void "produces several beans from a factory field holding an array"() {
        when:
        def context = buildContext('''
package valueprovider

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

class Widget(val name: String)

@Factory
class WidgetFactory:
  @Bean
  val widgets: Array[Widget] = Array(new Widget("one"), new Widget("two"))
''', [:], true)
        def widgetType = context.classLoader.loadClass('valueprovider.Widget')

        then: 'the array is unpacked into one bean per element, as a method returning one is'
        context.getBeansOfType(widgetType).size() == 2
        context.getBeansOfType(widgetType).collect { it.name() }.toSet() == ['one', 'two'] as Set

        cleanup:
        context?.close()
    }
}
