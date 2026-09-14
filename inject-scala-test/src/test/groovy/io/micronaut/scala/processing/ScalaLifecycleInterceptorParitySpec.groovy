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
 * P2 parity, ported from {@code inject-java}'s {@code PostConstructInterceptorCompileSpec} and
 * {@code PreDestroyInterceptorCompileSpec}.
 *
 * <p>An interceptor can be bound to a lifecycle point rather than to method calls, by giving
 * {@code @InterceptorBinding} a {@code kind}. Nothing here covered that: every interceptor
 * tested so far runs on method invocation, where getting the binding kind wrong is invisible.</p>
 *
 * <p>Declaring it needs {@code @InterceptorBinding} twice on one annotation, and it is
 * {@code @Repeatable}. Scala has no repeatable annotations, so the container has to be written
 * out by hand as {@code @InterceptorBindingDefinitions} -- which means Core reads the binding
 * kinds through the explicit-container path rather than the one Java exercises. That path is
 * the same one the {@code @AliasFor} container fix on this branch had to synthesise, and it is
 * worth a test that reaches it from source rather than from a synthesised value.</p>
 */
class ScalaLifecycleInterceptorParitySpec extends AbstractScalaTypeElementSpec {

    void "binds interceptors to construction and destruction as well as invocation"() {
        when:
        def context = buildContext('''
package lifecycleinterceptor

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.InterceptorBinding
import io.micronaut.aop.InterceptorBindingDefinitions
import io.micronaut.aop.InterceptorKind
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Factory
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
@InterceptorBindingDefinitions(Array(
  new InterceptorBinding(kind = InterceptorKind.POST_CONSTRUCT),
  new InterceptorBinding(kind = InterceptorKind.PRE_DESTROY)
))
class TestAnn extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[TestAnn]

@Singleton
@TestAnn
class MyBean:
  var invoked: Int = 0

  def test(): Unit = ()

  @PostConstruct
  def init(): Unit = invoked = invoked + 1

class MyOtherBean

@Factory
class MyFactory:
  @TestAnn
  @Singleton
  def test(): MyOtherBean = new MyOtherBean

@Singleton
@InterceptorBean(Array(classOf[TestAnn]))
class TestInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()

@Singleton
@InterceptorBinding(value = classOf[TestAnn], kind = InterceptorKind.POST_CONSTRUCT)
class PostConstructTestInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()

@Singleton
@InterceptorBinding(value = classOf[TestAnn], kind = InterceptorKind.PRE_DESTROY)
class PreDestroyTestInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()
''')
        def around = getBean(context, 'lifecycleinterceptor.TestInterceptor')
        def onConstruct = getBean(context, 'lifecycleinterceptor.PostConstructTestInterceptor')
        def onDestroy = getBean(context, 'lifecycleinterceptor.PreDestroyTestInterceptor')

        then: 'nothing has been constructed yet'
        around.invoked() == 0
        onConstruct.invoked() == 0
        onDestroy.invoked() == 0

        when: 'the advised bean is created'
        def instance = getBean(context, 'lifecycleinterceptor.MyBean')

        then: 'the post-construct binding fires, and the bean still ran its own hook'
        instance.invoked() == 1
        onConstruct.invoked() == 1
        onDestroy.invoked() == 0

        when: 'a method is called'
        def beforeCall = around.invoked()
        instance.test()

        then: 'invocation reaches the method interceptor, not the lifecycle-bound ones'
        instance instanceof Intercepted
        around.invoked() == beforeCall + 1
        onConstruct.invoked() == 1
        onDestroy.invoked() == 0

        when: 'a bean the factory produced under the same annotation is created'
        getBean(context, 'lifecycleinterceptor.MyOtherBean')

        then: 'construction advice applies to factory-produced beans too'
        onConstruct.invoked() == 2

        when:
        context.close()

        then: 'and the pre-destroy binding fires only on shutdown'
        onDestroy.invoked() > 0
    }
}
