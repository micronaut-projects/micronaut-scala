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

import io.micronaut.aop.Intercepted
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ScalaAopParitySpec extends AbstractScalaTypeElementSpec {

    void "applies around advice to inherited Scala trait methods"() {
        when:
        def context = buildContext('''
package aopparity

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.METHOD))
@Around
class Counted extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Counted]

@Singleton
@InterceptorBean(Array(classOf[Counted]))
class CountedInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()

trait Worker:
  @Counted
  def work(name: String): String = "work-" + name

@Singleton
class DefaultWorker extends Worker
''')
        def worker = getBean(context, 'aopparity.DefaultWorker')
        def interceptor = getBean(context, 'aopparity.CountedInterceptor')

        then:
        worker instanceof Intercepted
        worker.work('one') == 'work-one'
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "supports Scala introduction with around advice and additional interfaces"() {
        when:
        def context = buildContext('''
package aopparity

import io.micronaut.aop.Around
import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

trait CustomProxy:
  def isProxy(): Boolean

@Singleton
class ProxyAdviceInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.getMethodName match
      case "getId" => java.lang.Long.valueOf(99L)
      case "isProxy" => java.lang.Boolean.TRUE
      case _ => context.proceed()

@Around
@Introduction(interfaces = Array(classOf[CustomProxy]))
@Type(Array(classOf[ProxyAdviceInterceptor]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class ProxyIntroductionAndAround extends StaticAnnotation

@ProxyIntroductionAndAround
trait BookService:
  def getId(): java.lang.Long
''')
        def service = getBean(context, 'aopparity.BookService')
        def customProxyType = context.classLoader.loadClass('aopparity.CustomProxy')

        then:
        service instanceof Intercepted
        customProxyType.isInstance(service)
        service.getId() == 99L
        service.isProxy()

        cleanup:
        context?.close()
    }

    void "reports final Scala method AOP errors"() {
        when:
        buildContext('''
package aopfinal

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.METHOD))
@Around
class Counted extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Counted]

@Singleton
@InterceptorBean(Array(classOf[Counted]))
class CountedInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.proceed()

@Singleton
class FinalService:
  @Counted
  final def call(): String = "done"
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Method defines AOP advice but is declared final')
    }

    void "supports Scala adapter methods"() {
        when:
        def context = buildContext('''
package adapterparity

import io.micronaut.aop.Adapter
import io.micronaut.context.event.StartupEvent
import jakarta.inject.Singleton

@Singleton
class EventHandlers:
  var invoked: Boolean = false

  @Adapter(classOf[io.micronaut.context.event.ApplicationEventListener[?]])
  def onStartup(event: StartupEvent): Unit =
    invoked = true
''', true)
        def handler = getBean(context, 'adapterparity.EventHandlers')
        def listener = context.getBeansOfType(ApplicationEventListener).find {
            it.getClass().name.contains('EventHandlers')
        }

        then:
        listener != null

        when:
        listener.onApplicationEvent(new StartupEvent(context))

        then:
        handler.invoked()

        cleanup:
        context?.close()
    }

    void "supports inherited Scala lifecycle hooks and factory preDestroy hooks"() {
        when:
        def context = buildContext('''
package lifecycleparity

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton

abstract class BaseService:
  var created: Boolean = false
  var destroyed: Boolean = false

  @PostConstruct
  def init(): Unit =
    created = true

  @PreDestroy
  def shutdown(): Unit =
    destroyed = true

@Singleton
class Service extends BaseService

class Resource:
  var closed: Boolean = false
  def close(): Unit =
    closed = true

@Factory
class ResourceFactory:
  @Bean(preDestroy = "close")
  @Singleton
  def resource(): Resource = Resource()
''')
        def service = getBean(context, 'lifecycleparity.Service')
        def resource = getBean(context, 'lifecycleparity.Resource')

        then:
        service.created()
        !service.destroyed()
        !resource.closed()

        when:
        context.close()

        then:
        service.destroyed()
        resource.closed()
    }

    void "exposes inherited overloaded Scala executable methods"() {
        when:
        def definition = buildBeanDefinition('executableparity.DefaultOperations', '''
package executableparity

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

trait Operations:
  @Executable
  def convert(value: String): String = value

  @Executable
  def convert(value: Int): Int = value

@Singleton
class DefaultOperations extends Operations
''')

        then:
        definition.findMethod('convert', String).present
        definition.findMethod('convert', Integer.TYPE).present
        definition.executableMethods.findAll { it.methodName == 'convert' }.size() == 2
    }
}
