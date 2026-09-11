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
 * A Scala {@code object} is the idiomatic singleton, and every module class used to be
 * skipped by the extractor, so {@code @Singleton object} and {@code @Factory object}
 * produced no bean, no error and no warning at all.
 *
 * An {@code object} compiles to a class with a *private* constructor and a public static
 * {@code MODULE$} holding the single instance, so Micronaut cannot construct it. It is
 * modelled as a factory producing that field, which also keeps the injected bean identical
 * to the instance Scala code reaches through {@code Config}.
 */
class ScalaObjectBeanSpec extends AbstractScalaTypeElementSpec {

    void 'a singleton object is a bean, and it is the MODULE$ instance'() {
        given:
        def context = buildContext('''
package test

import jakarta.inject.Singleton

@Singleton
object Config {
  def name(): String = "config"
}
''', false)
        def objectClass = context.classLoader.loadClass('test.Config$')

        when:
        def definitions = context.getAllBeanDefinitions().findAll {
            it.getBeanType().getName().startsWith('test.')
        }

        then: 'exactly one definition -- the factory itself must not also be registered'
        definitions.size() == 1

        when:
        def bean = context.getBean(objectClass)

        then: 'the bean is the object, not a second instance built through the private constructor'
        bean.is(objectClass.getField('MODULE$').get(null))
        bean.name() == 'config'

        cleanup:
        context?.close()
    }

    void 'a singleton object can be injected'() {
        given:
        def context = buildContext('''
package test

import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
object Config {
  def name(): String = "config"
}

@Singleton
class Consumer @Inject() (val config: Config.type)
''', false)

        when:
        def consumer = context.getBean(context.classLoader.loadClass('test.Consumer'))

        then:
        consumer.config().is(context.classLoader.loadClass('test.Config$').getField('MODULE$').get(null))

        cleanup:
        context?.close()
    }

    void 'a factory object produces beans from its methods'() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
object Beans {
  @Bean
  @Singleton
  def greeting(): String = "from-factory"
}
''', false)

        expect:
        context.getBean(String) == 'from-factory'

        cleanup:
        context?.close()
    }

    void 'an object with no annotations is not a bean'() {
        given:
        def context = buildContext('''
package test

object Plain {
  def name(): String = "plain"
}

class Marker
''', false)

        expect: 'an ordinary companion or namespace object must not become a bean'
        context.getAllBeanDefinitions().findAll { it.getBeanType().getName().startsWith('test.') }.isEmpty()

        cleanup:
        context?.close()
    }
}
