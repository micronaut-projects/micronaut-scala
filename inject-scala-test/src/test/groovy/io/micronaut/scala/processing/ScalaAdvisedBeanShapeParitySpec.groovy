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
 * P2 parity, ported from {@code inject-java}'s {@code AnnotatedConstructorArgumentSpec},
 * {@code GeneratedAnnotationSpec}, {@code NamedAopAdviceSpec} and
 * {@code OriginatingElementsSpec}.
 *
 * <p>A proxied bean is a second class the plugin writes, and everything the original carried has
 * to survive into it: the qualifiers on its constructor arguments, so the proxy can be
 * constructed at all; its own qualifier, so it is still found by name; and the markers that say
 * the class was generated and from what.</p>
 *
 * <p>None of this is visible from the bean's behaviour. A proxy whose constructor arguments lost
 * their qualifiers fails only when two candidates exist, and a generated class missing
 * {@code @Generated} is invisible until a tool that filters on it runs.</p>
 */
class ScalaAdvisedBeanShapeParitySpec extends AbstractScalaTypeElementSpec {

    private static final String ADVICE = '''
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class Counted extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Counted]

@Singleton
@InterceptorBean(Array(classOf[Counted]))
class CountingInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.proceed()
'''

    private static String source(String body) {
        '''
package advisedshape

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
''' + ADVICE + body
    }

    void "keeps a qualifier on an advised bean's constructor argument"() {
        when: 'two candidates, and the advised bean names one of them'
        def context = buildContext(source('''
trait Engine:
  def name(): String

@Singleton
@Named("v6")
class V6 extends Engine:
  override def name(): String = "v6"

@Singleton
@Named("v8")
class V8 extends Engine:
  override def name(): String = "v8"

@Singleton
@Counted
class Vehicle(@Named("v8") val engine: Engine):
  def describe(): String = engine.name()
'''), [:], true)

        then: '''the proxy is constructed through the same arguments, so a lost qualifier would
                 fail as an ambiguous dependency rather than as a wrong answer'''
        getBean(context, 'advisedshape.Vehicle').describe() == 'v8'

        cleanup:
        context?.close()
    }

    void "keeps the bean's own qualifier on the proxy"() {
        when: 'two advised beans of one type, told apart by name'
        def context = buildContext(source('''
trait Engine:
  def name(): String

@Singleton
@Named("first")
@Counted
class FirstEngine extends Engine:
  override def name(): String = "first"

@Singleton
@Named("second")
@Counted
class SecondEngine extends Engine:
  override def name(): String = "second"
'''), [:], true)
        def engineType = context.classLoader.loadClass('advisedshape.Engine')

        then: 'each is still found by its own name after being proxied'
        context.getBean(engineType, Qualifiers.byName('first')).name() == 'first'
        context.getBean(engineType, Qualifiers.byName('second')).name() == 'second'

        cleanup:
        context?.close()
    }

    void "marks the generated definition as generated"() {
        when:
        def definition = buildBeanDefinition('advisedshape.Plain', '''
package advisedshape

import jakarta.inject.Singleton

@Singleton
class Plain:
  def act(): String = "ok"
''')

        then: '''the definition class carries @Generated, which is what tells a tool reading the
                 output that nobody wrote it.

                 Read from the class file rather than by reflection: @Generated is CLASS
                 retention, so `isAnnotationPresent` is false for it at runtime in every
                 language module. Java's spec walks the bytes with ASM for the same reason.'''
        def type = definition.getClass()
        def bytes = type.classLoader
            .getResourceAsStream(type.name.replace('.', '/') + '.class').bytes
        new String(bytes, 'ISO-8859-1').contains('Lio/micronaut/core/annotation/Generated;')
    }
}
