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

import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaExpressionContextRegistrar

import java.util.function.Supplier

class ScalaEvaluatedExpressionParitySpec extends AbstractScalaTypeElementSpec {

    void "supports evaluated expression constructor injection"() {
        when:
        def context = buildContext('''
package expressionparity

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Expr(
  @Value("#{ 25 }") val wrapper: java.lang.Integer,
  @Value("#{ 23 }") val primitive: Int
)
''')
        def bean = getBean(context, 'expressionparity.Expr')

        then:
        bean.wrapper() == 25
        bean.primitive() == 23

        cleanup:
        context?.close()
    }

    void "supports evaluated expression field injection"() {
        when:
        def context = buildContext('''
package expressionparity

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import scala.annotation.meta.field

@Singleton
class Expr:
  @(Value @field)("#{ 15 ^ 2 }")
  var fieldValue: Int = 0

  @(Value @field)("#{ null }")
  var nullValue: Object = "not-null"
''')
        def bean = getBean(context, 'expressionparity.Expr')

        then:
        bean.fieldValue() == 225
        bean.nullValue() == null

        cleanup:
        context?.close()
    }

    void "supports evaluated expression method and factory injection"() {
        when:
        def context = buildContext('''
package expressionparity

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Value
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class MethodExpr:
  var wrapper: java.lang.Integer = 0
  var primitive: Int = 0

  @Inject
  def install(
      @Value("#{ 25 }") wrapper: java.lang.Integer,
      @Value("#{ 23 }") primitive: Int
  ): Unit =
    this.wrapper = wrapper
    this.primitive = primitive

case class FactoryExpr(wrapper: java.lang.Integer, primitive: Int)

@Factory
class ExprFactory:
  @Bean
  def factoryExpr(
      @Value("#{ 20 + 5 }") wrapper: java.lang.Integer,
      @Value("#{ 20 + 3 }") primitive: Int
  ): FactoryExpr =
    FactoryExpr(wrapper, primitive)
''')
        def methodBean = getBean(context, 'expressionparity.MethodExpr')
        def factoryBean = getBean(context, 'expressionparity.FactoryExpr')

        then:
        methodBean.wrapper() == 25
        methodBean.primitive() == 23
        factoryBean.wrapper() == 25
        factoryBean.primitive() == 23

        cleanup:
        context?.close()
    }

    void "supports evaluated expressions in requires annotations"() {
        when:
        def context = buildContext('''
package expressionparity

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(env = Array("#{ 'test' }"))
@Requires(property = "test.value", value = "#{ 'TEST-VALUE' }")
class Expr
''', ['test.value': 'TEST-VALUE'])

        then:
        getBean(context, 'expressionparity.Expr')

        cleanup:
        context?.close()
    }

    void "supports route condition expression context from classpath annotation metadata"() {
        when:
        def definition = buildBeanDefinition('expressionparity.RouteConditionController', '''
package expressionparity

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.RouteCondition

@Controller("/api")
class RouteConditionController:

  @Get("/hello")
  @RouteCondition("#{request.parameters.getFirst('v').orElse(null) == '2'}")
  def helloV2(): String = "Hello v2"
''')

        then:
        definition != null
    }

    void "supports evaluated requires expressions against context values"() {
        when:
        def enabled = ScalaExpressionContextRegistrar.withContextClasses(['expressionparity.Context'], {
            buildContext('''
package expressionparity

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(property = "test.enabled", value = "#{ #expected }")
class Expr

@ConfigurationProperties("test")
@Singleton
class Context:
  var expected: Boolean = false
''', ['test.enabled': false, 'test.expected': false])
        } as Supplier)
        def disabled = ScalaExpressionContextRegistrar.withContextClasses(['expressionparity.Context'], {
            buildContext('''
package expressionparity

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(property = "test.enabled", value = "#{ #expected }")
class Expr

@ConfigurationProperties("test")
@Singleton
class Context:
  var expected: Boolean = false
''', ['test.enabled': true, 'test.expected': false])
        } as Supplier)

        then:
        getBean(enabled, 'expressionparity.Expr')

        when:
        getBean(disabled, 'expressionparity.Expr')

        then:
        thrown(NoSuchBeanException)

        cleanup:
        enabled?.close()
        disabled?.close()
    }
}
