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
 * P2 parity, ported from {@code inject-java}'s {@code FinalModifierSpec},
 * {@code InheritedAnnotationMetadataSpec}, {@code PropertyAdviceSpec} and
 * {@code IntroductionAdviceWithNewInterfaceSpec}.
 *
 * <p>Which members a proxy can advise is decided by what it can override, and which annotations
 * it carries is decided by where they were written. A final member cannot be overridden, a
 * property accessor is a method like any other, and an interface an introduction adds is one the
 * class never mentions.</p>
 *
 * <p>Scala changes what each of those looks like. `final` is written on a `def` or a `class`
 * rather than being the default, a property accessor is generated rather than authored, and the
 * added interface is a trait the proxy must implement without the bean ever naming it.</p>
 */
class ScalaAdviceApplicationParitySpec extends AbstractScalaTypeElementSpec {

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
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()
'''

    private static String source(String body) {
        '''
package adviceapply

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
''' + ADVICE + body
    }

    void "refuses to advise a final class"() {
        when: 'a proxy would have to extend it, and cannot'
        buildContext(source('''
@Singleton
@Counted
final class Sealed:
  def act(): String = "done"
'''), [:], true)

        then: 'the compilation fails rather than producing a bean without advice'
        thrown(Throwable)
    }

    void "advises a property accessor like any other method"() {
        when: 'the advised member is a var, so the accessors are generated'
        def context = buildContext(source('''
@Singleton
@Counted
class Holder:
  var name: String = "value"
'''), [:], true)
        def holder = getBean(context, 'adviceapply.Holder')
        def interceptor = getBean(context, 'adviceapply.CountingInterceptor')

        then:
        holder instanceof Intercepted

        when: 'reading and writing the property'
        def before = interceptor.invoked()
        holder.name()
        holder.name_$eq('other')

        then: 'both generated accessors were advised, and the write took effect through the proxy'
        interceptor.invoked() == before + 2
        holder.name() == 'other'

        cleanup:
        context?.close()
    }

    void "implements an interface an introduction adds that the bean never names"() {
        when:
        def context = buildContext(source('''
trait Extra:
  def extra(): String

@Introduction(interfaces = Array(classOf[Extra]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stubbed extends StaticAnnotation

@Singleton
@Stubbed
@Counted
class Service:
  def act(): String = "done"
'''), [:], true)
        def service = getBean(context, 'adviceapply.Service')

        then: 'the proxy implements the added interface, which the class does not extend'
        service instanceof Intercepted
        context.classLoader.loadClass('adviceapply.Extra').isInstance(service)

        cleanup:
        context?.close()
    }
}
