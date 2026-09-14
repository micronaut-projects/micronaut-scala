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

import io.micronaut.inject.AdvisedBeanType
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P2 parity, ported from {@code inject-java}'s {@code IntroductionAnnotationSpec} and
 * {@code AbstractClassIntroductionSpec}.
 *
 * <p>Introduction advice divides a type in two: abstract members are implemented by the
 * interceptor, concrete ones keep their own body. Everything covered here so far introduced
 * a fully abstract trait, so the division was never tested -- sending every member to the
 * interceptor, or none, passes an all-abstract type identically.</p>
 *
 * <p>The mixed type is the ordinary Scala one. Java needs an abstract class, or default methods,
 * to write a partly-implemented type; a Scala trait carries abstract and concrete members side
 * by side as a matter of course, so a Scala user meets this shape first rather than last.</p>
 */
class ScalaIntroductionAdviceParitySpec extends AbstractScalaTypeElementSpec {

    private static final String PREAMBLE = '''
package introductionparity

import io.micronaut.aop.Around
import io.micronaut.aop.Introduction
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Singleton
class StubIntroduction extends MethodInterceptor[AnyRef, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[AnyRef, Object]): Object =
    invoked = invoked + 1
    "introduced"

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class Shout extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Shout]

@Singleton
@InterceptorBean(Array(classOf[Shout]))
class ShoutInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    String.valueOf(context.proceed()) + "!"
'''

    void "implements only the abstract members of an introduced trait"() {
        when: 'a trait carrying one abstract and one concrete method'
        def context = buildContext(PREAMBLE + '''
@Stub
trait MyBean:
  def test(): String
  def test2(): String = "good"
''')
        def bean = getBean(context, 'introductionparity.MyBean')
        def interceptor = getBean(context, 'introductionparity.StubIntroduction')

        then: 'the abstract member is what the interceptor implements'
        bean.test() == 'introduced'
        interceptor.invoked() == 1

        and: 'the concrete member keeps its own body and never reaches the interceptor'
        bean.test2() == 'good'
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "implements only the abstract members of an introduced abstract class"() {
        when:
        def context = buildContext(PREAMBLE + '''
@Stub
abstract class MyBean:
  def test(): String
  def test2(): String = "good"
''')
        def definition = getBeanDefinition(context, 'introductionparity.MyBean')
        def bean = getBean(context, 'introductionparity.MyBean')
        def interceptor = getBean(context, 'introductionparity.StubIntroduction')

        then: 'the generated definition names the type it stands in for'
        definition instanceof AdvisedBeanType
        definition.interceptedType.name == 'introductionparity.MyBean'

        and:
        bean.test() == 'introduced'
        bean.test2() == 'good'
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "applies a concrete member's own around advice inside an introduced trait"() {
        when: 'the concrete method also carries around advice of its own'
        def context = buildContext(PREAMBLE + '''
@Stub
trait MyBean:
  def test(): String

  @Shout
  def test2(): String = "good"
''')
        def bean = getBean(context, 'introductionparity.MyBean')
        def interceptor = getBean(context, 'introductionparity.StubIntroduction')

        then: 'the introduction interceptor still implements the abstract member'
        bean.test() == 'introduced'
        interceptor.invoked() == 1

        and: 'and the concrete member runs its own body through its own advice'
        bean.test2() == 'good!'
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }
}
