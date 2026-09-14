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
 * P2 parity, ported from {@code inject-java}'s {@code PostConstructCompileSpec},
 * {@code PreDestroyCompileSpec} and the two {@code BeanWithPostConstructSpec} sources.
 *
 * <p>Lifecycle coverage here asserts that a hook runs. These assert <em>how many times</em> and
 * <em>whether at all</em> for the shapes where that is decided differently: a hook inherited
 * alongside one declared, which must not run twice; a private hook, which is invoked
 * reflectively because nothing can call it; and destruction, which has to run for a singleton at
 * shutdown and not before.</p>
 *
 * <p>Scala makes the private case ordinary rather than unusual -- a `private def` is how you say
 * a hook is nobody else's business -- and the inherited case usually arrives from a trait, where
 * the hook is an interface default method rather than a class member.</p>
 */
class ScalaLifecycleHookParitySpec extends AbstractScalaTypeElementSpec {

    void "runs an inherited hook and a declared one, each exactly once"() {
        when:
        def context = buildContext('''
package lifecyclehook

import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton

object Trace:
  var order: List[String] = Nil

abstract class Base:
  @PostConstruct
  def baseInit(): Unit = Trace.order = Trace.order :+ "base"

@Singleton
class Child extends Base:
  @PostConstruct
  def childInit(): Unit = Trace.order = Trace.order :+ "child"
''', [:], true)
        getBean(context, 'lifecyclehook.Child')
        def trace = context.classLoader.loadClass('lifecyclehook.Trace$').getField('MODULE$').get(null)

        then: '''both ran, and neither twice -- an inherited hook that is also collected as a
                 declared one runs a second time, which is the failure worth catching here.

                 The order is deliberately not asserted. None of the Java specs this is ported
                 from states one, so pinning `base` before `child` would fix a detail Micronaut
                 does not promise; the observed order here is child first.'''
        trace.order().size() == 2
        trace.order().contains('base')
        trace.order().contains('child')

        cleanup:
        context?.close()
    }

    void "runs a private post-construct hook"() {
        when: 'nothing outside the class can call it, so it has to be invoked reflectively'
        def context = buildContext('''
package lifecyclehook

import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton

@Singleton
class Secretive:
  var initialised: Boolean = false

  @PostConstruct
  private def init(): Unit = initialised = true
''', [:], true)

        then:
        getBean(context, 'lifecyclehook.Secretive').initialised()

        cleanup:
        context?.close()
    }

    void "runs a trait's hook on the class that mixes it in"() {
        when: 'the hook is a concrete trait method, so an interface default on the class'
        def context = buildContext('''
package lifecyclehook

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton

trait Managed:
  var started: Boolean = false
  var stopped: Boolean = false

  @PostConstruct
  def onStart(): Unit = started = true

  @PreDestroy
  def onStop(): Unit = stopped = true

@Singleton
class Service extends Managed
''', [:], true)
        def service = getBean(context, 'lifecyclehook.Service')

        then: 'construction ran the hook the trait declares'
        service.started()
        !service.stopped()

        when:
        context.close()

        then: 'and destruction waited for shutdown'
        service.stopped()
    }
}
