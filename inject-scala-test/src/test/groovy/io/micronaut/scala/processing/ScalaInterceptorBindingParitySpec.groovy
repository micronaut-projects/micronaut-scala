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
 * P2 parity, ported from {@code inject-java}'s {@code AroundCompileSpec}.
 *
 * <p>Every advice case here binds an interceptor by annotation <em>type</em>. Core can also bind
 * by annotation <em>member values</em>: with {@code @InterceptorBinding(bindMembers = true)} two
 * interceptors carrying the same annotation with different members are told apart by those
 * values, and a member marked {@code @NonBinding} is left out of the comparison.</p>
 *
 * <p>That reads annotation members out of the written metadata and compares them, so it depends
 * on the whole annotation path this branch has been repairing -- member order, defaults, and
 * aliasing. Binding by type alone never exercised any of it, because the type survives almost
 * any mistake in the members.</p>
 */
class ScalaInterceptorBindingParitySpec extends AbstractScalaTypeElementSpec {

    void "binds interceptors by annotation member values"() {
        when: 'two interceptors declare the same annotation with different members'
        def context = buildContext('''
package memberbinding

import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.InterceptorBinding
import io.micronaut.aop.Interceptor
import io.micronaut.aop.InvocationContext
import io.micronaut.context.annotation.NonBinding
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@InterceptorBinding(bindMembers = true)
class TestAnn(val num: Int, @NonBinding val debug: Boolean = false)
  extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[TestAnn]

@Singleton
@TestAnn(num = 1, debug = false)
class MyBean:
  def test(): Unit = ()

  @TestAnn(num = 2)
  def test2(): Unit = ()

@Singleton
@InterceptorBean(Array(classOf[TestAnn]))
@TestAnn(num = 1, debug = true)
class TestInterceptor extends Interceptor[Object, Object]:
  var invoked: Boolean = false

  override def intercept(context: InvocationContext[Object, Object]): Object =
    invoked = true
    context.proceed()

@Singleton
@InterceptorBean(Array(classOf[TestAnn]))
@TestAnn(num = 2)
class AnotherInterceptor extends Interceptor[Object, Object]:
  var invoked: Boolean = false

  override def intercept(context: InvocationContext[Object, Object]): Object =
    invoked = true
    context.proceed()
''')
        def instance = getBean(context, 'memberbinding.TestInterceptor')
        def another = getBean(context, 'memberbinding.AnotherInterceptor')
        def bean = getBean(context, 'memberbinding.MyBean')
        bean.test()

        then: '''num = 1 selects the first interceptor even though the two disagree on debug,
                 which is @NonBinding and so plays no part in the comparison'''
        bean instanceof Intercepted
        instance.invoked()
        !another.invoked()

        when: 'the method-level annotation overrides the one on the type'
        instance.invoked_$eq(false)
        bean.test2()

        then:
        !instance.invoked()
        another.invoked()

        cleanup:
        context?.close()
    }

    void "applies every advice annotation a method carries"() {
        when: 'two unrelated around annotations on one method'
        def context = buildContext('''
package annbinding2

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.Interceptor
import io.micronaut.aop.InvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class TestAnn extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[TestAnn]

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class TestAnn2 extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[TestAnn2]

@Singleton
class MyBean:
  @TestAnn
  @TestAnn2
  def test(): Unit = ()

@Singleton
@InterceptorBean(Array(classOf[TestAnn]))
class TestInterceptor extends Interceptor[Object, Object]:
  var invoked: Boolean = false

  override def intercept(context: InvocationContext[Object, Object]): Object =
    invoked = true
    context.proceed()

@Singleton
@InterceptorBean(Array(classOf[TestAnn2]))
class AnotherInterceptor extends Interceptor[Object, Object]:
  var invoked: Boolean = false

  override def intercept(context: InvocationContext[Object, Object]): Object =
    invoked = true
    context.proceed()
''')
        def bean = getBean(context, 'annbinding2.MyBean')
        def interceptor = getBean(context, 'annbinding2.TestInterceptor')
        def another = getBean(context, 'annbinding2.AnotherInterceptor')
        bean.test()

        then: 'neither annotation hides the other'
        bean instanceof Intercepted
        interceptor.invoked()
        another.invoked()

        cleanup:
        context?.close()
    }
}
