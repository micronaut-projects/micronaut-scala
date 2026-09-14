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
 * P2 parity, ported from {@code inject-java}'s {@code AdviceDefinedOnFactorySpec}.
 *
 * <p>Advice at the class level of a {@code @Factory} advises the factory's own methods and
 * <em>not</em> the beans it produces. That is easy to get backwards, and backwards is worse than
 * broken: every bean the factory makes would be silently proxied, which changes their identity
 * and their lifecycle without anything reporting a problem.</p>
 *
 * <p>Nothing here separated the two. The factory-advice cases covered so far put the annotation
 * on the producing <em>method</em>, where it advises the produced bean and the question does not
 * arise.</p>
 */
class ScalaFactoryAdviceParitySpec extends AbstractScalaTypeElementSpec {

    void "advises a factory's own methods and not the beans it produces"() {
        when:
        def context = buildContext('''
package factoryadvice

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

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

class Product:
  def describe(): String = "product"

@Factory
@Counted
class ProductFactory:
  @Bean
  @Executable
  def product(): Product = new Product
''', [:], true)
        def interceptor = getBean(context, 'factoryadvice.CountingInterceptor')
        def factory = getBean(context, 'factoryadvice.ProductFactory')

        then: 'the factory itself is the proxied bean'
        factory instanceof Intercepted

        when: 'the produced bean is resolved, which runs the advised producer'
        def product = getBean(context, 'factoryadvice.Product')
        def afterProduction = interceptor.invoked()

        then: 'the producer method was advised, so the interceptor ran'
        afterProduction > 0

        and: 'but the bean it produced is a plain instance, not a proxy'
        !(product instanceof Intercepted)

        when: 'calling a method on the produced bean'
        product.describe() == 'product'

        then: 'nothing is intercepted -- the advice never reached the produced type'
        interceptor.invoked() == afterProduction

        cleanup:
        context?.close()
    }
}
