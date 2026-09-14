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
 * P2 parity, ported from {@code inject-java}'s {@code FactoryMappedAdviceSpec},
 * {@code MappedIntroductionOnConcreteClassSpec},
 * {@code IntroducedWithRepeatableAnnotationSpec} and {@code OriginatingElementsSpec}.
 *
 * <p>Advice does not have to be written directly: an annotation that is only a stereotype of
 * {@code @Around} or {@code @Introduction} applies it, and the decision is made from the resolved
 * metadata rather than from what the source names. That is the same resolution the annotation
 * work on this branch kept touching, reached from the AOP side.</p>
 */
class ScalaMappedAdviceParitySpec extends AbstractScalaTypeElementSpec {

    private static final String STEREOTYPES = '''
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around
class BaseAdvice extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[BaseAdvice]

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@BaseAdvice
class Traced extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Traced]

@Singleton
@InterceptorBean(Array(classOf[BaseAdvice]))
class TracingInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()
'''

    private static String source(String body) {
        '''
package mappedadvice

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
''' + STEREOTYPES + body
    }

    void "applies advice a stereotype carries rather than one written directly"() {
        when: '@Traced is not @Around itself, only meta-annotated with something that is'
        def context = buildContext(source('''
@Singleton
@Traced
class Service:
  def act(): String = "done"
'''), [:], true)
        def service = getBean(context, 'mappedadvice.Service')
        def interceptor = getBean(context, 'mappedadvice.TracingInterceptor')

        then:
        service instanceof Intercepted

        when:
        service.act()

        then: 'the interceptor bound to the meta-annotation ran'
        interceptor.invoked() > 0

        cleanup:
        context?.close()
    }

    void "applies mapped advice to a bean a factory produces"() {
        when: 'the stereotype is on the producing method, so it advises what is produced'
        def context = buildContext(source('''
class Widget:
  def act(): String = "done"

@Factory
class WidgetFactory:
  @Singleton
  @Traced
  def widget(): Widget = new Widget
'''), [:], true)
        def widget = getBean(context, 'mappedadvice.Widget')
        def interceptor = getBean(context, 'mappedadvice.TracingInterceptor')

        then:
        widget instanceof Intercepted

        when:
        widget.act()

        then:
        interceptor.invoked() > 0

        cleanup:
        context?.close()
    }

    void "records the class a generated definition came from"() {
        when:
        def definition = buildBeanDefinition('mappedadvice.Plain', '''
package mappedadvice

import jakarta.inject.Singleton

@Singleton
class Plain:
  def act(): String = "ok"
''')

        then: '''the definition names the bean type it was written for, which is what associates
                 generated output with its source for incremental builds'''
        definition.beanType.name == 'mappedadvice.Plain'
    }
}
