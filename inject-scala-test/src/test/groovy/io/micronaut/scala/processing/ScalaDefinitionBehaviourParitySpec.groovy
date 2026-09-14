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
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity for four behaviours of the generated definition that nothing here covered:
 * type arguments surviving onto an AOP proxy, a diagnostic for a type that does not
 * resolve, {@code @Executable(processOnStartup)}, and the list form of
 * {@code @EachProperty}.
 */
class ScalaDefinitionBehaviourParitySpec extends AbstractScalaTypeElementSpec {

    void 'an AOP proxy keeps the type arguments of what it proxies'() {
        given:
        def context = buildContext('''
package test

import io.micronaut.aop.Around
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.*

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
@Type(Array(classOf[Noop]))
class Mutating extends scala.annotation.StaticAnnotation

@Singleton
class Noop extends MethodInterceptor[Object, Object] {
  override def intercept(context: MethodInvocationContext[Object, Object]): Object = context.proceed()
}

trait Contract[T] {
  def handle(item: T): T
}

@Mutating
@Singleton
class MyBean extends Contract[String] {
  override def handle(item: String): String = item
}
''', [:], true)

        when:
        def type = context.classLoader.loadClass('test.MyBean')
        def bean = context.getBean(type)

        then: 'the bean really is proxied'
        bean instanceof Intercepted

        and: 'and the proxy definition still resolves Contract[T] to String'
        context.getBeanDefinition(type).getTypeArguments('test.Contract')*.getType() == [String]

        and: 'and the advice does not break the call'
        bean.handle('x') == 'x'

        cleanup:
        context?.close()
    }

    void 'a type that does not resolve fails with a located diagnostic'() {
        when:
        buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Singleton

@Singleton
class Test(val missing: does.not.Exist)
''')

        then: 'the compiler reports it, rather than a model built around a missing type'
        def e = thrown(Throwable)
        e.message.contains('Not found')
        e.message.contains('Test.scala:')
    }

    void 'processOnStartup reaches the definition'() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Singleton
@Executable(processOnStartup = true)
class Test {
  def run(): String = "x"
}
''')

        expect:
        definition.getExecutableMethods()*.getName() == ['run']

        and: 'the flag core reads to decide when to run it'
        definition.getAnnotationMetadata()
                .booleanValue('io.micronaut.context.annotation.Executable', 'processOnStartup')
                .get()
    }

    void 'the list form of EachProperty binds an indexed list'() {
        given: 'the covered case is the map form; this is the list one'
        def context = buildContext('''
package test

import io.micronaut.context.annotation.EachProperty

@EachProperty(value = "servers", list = true)
class ServerConfig {
  var host: String = ""
}
''', ['servers[0].host': 'a', 'servers[1].host': 'b'], true)

        when:
        def beans = context.getBeansOfType(context.classLoader.loadClass('test.ServerConfig'))

        then:
        beans*.host().sort() == ['a', 'b']

        cleanup:
        context?.close()
    }
}
