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
import io.micronaut.context.annotation.Primary
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaBeanElementBuilderVisitor

import java.util.function.Supplier

class ScalaBeanElementBuilderParitySpec extends AbstractScalaTypeElementSpec {

    void "adds associated factory beans for Scala originating elements"() {
        when:
        def context = ScalaBeanElementBuilderVisitor.withMode(ScalaBeanElementBuilderVisitor.Mode.ASSOCIATED_FACTORY, {
            buildContext('''
package generatedbuilder

import io.micronaut.context.annotation.Prototype

@Prototype
class FactoryTrigger
''')
        } as Supplier)

        then:
        context.getBean(ScalaBeanElementBuilderVisitor.BeanA).name() == 'method'
        context.getBean(ScalaBeanElementBuilderVisitor.BeanB).name() == 'field'
        context.getBean(ScalaBeanElementBuilderVisitor.BeanC).name() == 'method'
        context.getBean(ScalaBeanElementBuilderVisitor.InterfaceA).name() == 'interface'

        cleanup:
        context?.close()
    }

    void "adds multiple generated factory beans with qualifiers"() {
        when:
        def context = ScalaBeanElementBuilderVisitor.withMode(ScalaBeanElementBuilderVisitor.Mode.MULTIPLE_FACTORIES, {
            buildContext('''
package generatedbuilder

import io.micronaut.context.annotation.Prototype

@Prototype
class MultipleFactoryTrigger
''')
        } as Supplier)
        def primaryDefinition = context.getBeanDefinition(ScalaBeanElementBuilderVisitor.BeanA)
        def otherDefinition = context.getBeanDefinition(
            ScalaBeanElementBuilderVisitor.BeanA,
            Qualifiers.byName('other')
        )

        then:
        context.getBean(ScalaBeanElementBuilderVisitor.BeanA).name() == 'primary'
        context.getBean(ScalaBeanElementBuilderVisitor.BeanA, Qualifiers.byName('other')).name() == 'other'
        primaryDefinition.hasAnnotation('test.Foo')
        primaryDefinition.hasAnnotation(Primary)
        !otherDefinition.hasAnnotation('test.Foo')
        otherDefinition.hasAnnotation('test.Bar')

        cleanup:
        context?.close()
    }

    void "adds executable method metadata to visitor generated beans"() {
        when:
        def context = ScalaBeanElementBuilderVisitor.withMode(ScalaBeanElementBuilderVisitor.Mode.EXECUTABLE_METHODS, {
            buildContext('''
package generatedbuilder

import jakarta.inject.Singleton

@Singleton
class ScheduledTrigger
''')
        } as Supplier)
        def definition = context.getBeanDefinition(ScalaBeanElementBuilderVisitor.ScheduledBean)
        def bean = context.getBean(ScalaBeanElementBuilderVisitor.ScheduledBean)
        def method = definition.getRequiredMethod('scheduleMe')
        def methodWithArgs = definition.getRequiredMethod('scheduleAnother', String, String)

        then:
        definition.requiresMethodProcessing()
        method.invoke(bean) == 'good'
        methodWithArgs.invoke(bean, '1', '2') == 'good 1 2'

        cleanup:
        context?.close()
    }

    void "applies #description AOP to visitor generated beans"() {
        when:
        def context = ScalaBeanElementBuilderVisitor.withMode(mode, {
            buildContext('''
package generatedaop

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.core.`type`.MutableArgumentValue
import io.micronaut.scala.processing.test.ScalaBeanElementBuilderVisitor.AopTarget
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Singleton
class AopClient(private val target: AopTarget):
  def hello(name: String): String = target.hello(name)
  def plain(name: String): String = target.plain(name)

@InterceptorBean(Array(classOf[Mutating]))
class MutatingInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    val parameterName = context.stringValue(classOf[Mutating]).orElse(null)
    val argument = context.getParameters().get(parameterName).asInstanceOf[MutableArgumentValue[Object]]
    if argument != null then argument.setValue("changed")
    context.proceed()

@Around
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.METHOD, ElementType.TYPE))
class Mutating extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Mutating]
''')
        } as Supplier)
        def client = getBean(context, 'generatedaop.AopClient')
        def target = context.getBean(ScalaBeanElementBuilderVisitor.AopTarget)

        then:
        target instanceof Intercepted
        client.hello('john') == 'Hello changed'
        client.plain('john') == expectedPlain

        cleanup:
        context?.close()

        where:
        mode                                                         | description    | expectedPlain
        ScalaBeanElementBuilderVisitor.Mode.AOP_ON_TYPE             | 'type-level'    | 'Hello changed'
        ScalaBeanElementBuilderVisitor.Mode.AOP_ON_METHOD           | 'method-level'  | 'Hello john'
    }
}
