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
 * P2 parity, ported from {@code inject-java}'s {@code PreDestroyOnBeanAnnotationSpec}.
 *
 * <p>{@code @Bean(preDestroy = "close")} names a method by string, so the name has to be
 * resolved against the produced type rather than read off an annotation on it. That covered case
 * here has one candidate and finds it. The cases that can go wrong are the ones with more than
 * one: an overload set, where picking the wrong arity throws at shutdown rather than at compile
 * time, and a name that resolves only by looking at what the type inherits.</p>
 *
 * <p>The inherited case is the Scala one. A produced type usually gets {@code close} from a
 * trait, so the lookup has to reach a concrete trait method -- which arrives as an interface
 * default method, not as a member of the class.</p>
 */
class ScalaFactoryPreDestroyParitySpec extends AbstractScalaTypeElementSpec {

    void "picks the no-argument overload when a preDestroy name is ambiguous"() {
        when: 'close is overloaded, and the other arity throws if it is ever called'
        def context = buildContext('''
package predestroy

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

class Resource:
  var closed: Boolean = false

  def close(context: Object): Unit =
    throw new RuntimeException("the wrong overload was chosen")

  def close(): Unit = closed = true

@Factory
class ResourceFactory:
  @Bean(preDestroy = "close")
  @Singleton
  def resource(): Resource = new Resource
''', [:], true)
        def resource = getBean(context, 'predestroy.Resource')

        then:
        !resource.closed()

        when:
        context.destroyBean(resource)

        then: 'the no-argument overload ran, and nothing threw'
        resource.closed()

        and: 'the singleton was discarded, so the next resolution builds a new one'
        !getBean(context, 'predestroy.Resource').is(resource)

        cleanup:
        context?.close()
    }

    void "resolves a preDestroy method the produced type inherits from a trait"() {
        when: 'close is a concrete trait method, so an interface default on the class'
        def context = buildContext('''
package predestroy

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

trait Closeable:
  var closed: Boolean = false
  def close(): Unit = closed = true

class Resource extends Closeable

@Factory
class ResourceFactory:
  @Bean(preDestroy = "close")
  @Singleton
  def resource(): Resource = new Resource
''', [:], true)
        def resource = getBean(context, 'predestroy.Resource')

        then:
        !resource.closed()

        when:
        context.close()

        then: 'the name resolved through the trait rather than the class body'
        resource.closed()
    }

    void "rejects a preDestroy name the produced type does not have"() {
        when:
        buildContext('''
package predestroy

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

class Resource

@Factory
class ResourceFactory:
  @Bean(preDestroy = "close")
  @Singleton
  def resource(): Resource = new Resource
''', [:], true)

        then: 'a name that resolves to nothing is a compile error, reported against the source position, not a silent no-op at shutdown'
        def e = thrown(Throwable)
        e.message.contains('@Bean method defines a preDestroy method that does not exist or is not public: close')

        cleanup:
        true
    }
}
