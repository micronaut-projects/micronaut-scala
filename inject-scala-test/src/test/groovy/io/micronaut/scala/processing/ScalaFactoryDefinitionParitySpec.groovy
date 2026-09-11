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

import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code FactoryBeanDefinitionSpec},
 * {@code FactoryBeanMethodSpec}, {@code FactoryWithScopedProxySpec} and
 * {@code FieldInjectionSpec}.
 *
 * <p>A factory writes one definition per producing member, and each carries the scope, qualifier
 * and dependencies of that member rather than of the factory. These assert the definition rather
 * than the bean: which scope it reports, that the factory itself is a bean too, and that a
 * producing member's own dependencies are resolved when it runs.</p>
 */
class ScalaFactoryDefinitionParitySpec extends AbstractScalaTypeElementSpec {

    void "makes the factory a bean alongside what it produces"() {
        when:
        def context = buildContext('''
package factorydef

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

class Widget(val name: String)

@Factory
class WidgetFactory:
  var built: Int = 0

  @Singleton
  def widget(): Widget =
    built = built + 1
    new Widget("made")
''', [:], true)
        def widget = getBean(context, 'factorydef.Widget')
        def factory = getBean(context, 'factorydef.WidgetFactory')

        then: 'the factory is resolvable, and is the object the producer ran on'
        widget.name() == 'made'
        factory.built() == 1

        cleanup:
        context?.close()
    }

    void "resolves a producing member's own dependencies when it runs"() {
        when: 'the producer takes a bean, not the factory'
        def context = buildContext('''
package factorydef

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Singleton
class Namer:
  def name(): String = "named"

class Widget(val name: String)

@Factory
class WidgetFactory:
  @Singleton
  def widget(namer: Namer): Widget = new Widget(namer.name())
''', [:], true)

        then:
        getBean(context, 'factorydef.Widget').name() == 'named'

        cleanup:
        context?.close()
    }

    void "gives each producing member its own scope and qualifier"() {
        when: 'one singleton and one prototype from the same factory'
        def context = buildContext('''
package factorydef

import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Named
import jakarta.inject.Singleton

class Widget(val name: String)

@Factory
class WidgetFactory:
  @Singleton
  @Named("shared")
  def shared(): Widget = new Widget("shared")

  @Prototype
  @Named("fresh")
  def fresh(): Widget = new Widget("fresh")
''', [:], true)
        def widgetType = context.classLoader.loadClass('factorydef.Widget')

        then: 'the singleton is the same instance twice'
        context.getBean(widgetType, Qualifiers.byName('shared'))
            .is(context.getBean(widgetType, Qualifiers.byName('shared')))

        and: 'and the prototype is not, from the same factory'
        !context.getBean(widgetType, Qualifiers.byName('fresh'))
            .is(context.getBean(widgetType, Qualifiers.byName('fresh')))

        cleanup:
        context?.close()
    }

    void "injects a field declared on the factory itself"() {
        when:
        def context = buildContext('''
package factorydef

import io.micronaut.context.annotation.Factory
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class Namer:
  def name(): String = "injected"

class Widget(val name: String)

@Factory
class WidgetFactory:
  @Inject
  var namer: Namer = null

  @Singleton
  def widget(): Widget = new Widget(namer.name())
''', [:], true)

        then: 'the factory is injected before any of its producers run'
        getBean(context, 'factorydef.Widget').name() == 'injected'

        cleanup:
        context?.close()
    }
}
