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
 * An annotation written on an <em>abstract</em> trait member, reaching the class that implements
 * it, for the kinds of annotation where being missed does something other than lose a method.
 *
 * <p>Putting the overridden declarations in a method's annotation hierarchy fixed
 * {@code @Executable}, which is read from the method's own metadata. It did not fix these, and
 * this spec exists to say so precisely rather than leave the earlier fix sounding general.
 * Each of them fails by doing nothing -- advice that never proxies, a constraint that never
 * validates, a qualifier that never qualifies -- so none would be noticed without an assertion.</p>
 *

 * <p>What decides whether a non-declared annotation crosses is {@code @Inherited}: core carries a
 * hierarchy annotation only when it is inherited or is a stereotype
 * ({@code AbstractAnnotationMetadataBuilder}). {@code @Executable} and {@code @Blocking} are
 * {@code @Inherited} and do cross; a constraint like {@code @NotBlank} is not, and would not
 * cross in Java either. Two drafts of this spec expected annotations that are not
 * {@code @Inherited} to be inherited, which is why the advice case read as broken and is not.</p>
 *
 * <p>Both the method and its parameters now carry what the trait declared. The third case is the
 * rule seen from the other side: {@code @Bean} is not {@code @Inherited}, so a producing member
 * declared only on the trait yields nothing, and that is the same answer Java gives.</p>
 *
 * <p>A concrete trait method cannot stand in for any of them. The trait's own method is inherited
 * whole and carries its annotations along, so this path is never taken.</p>
 */
class ScalaAbstractTraitMemberAnnotationSpec extends AbstractScalaTypeElementSpec {

    void "applies advice declared on an abstract trait method"() {
        when: 'the advice annotation is on the trait declaration, the body only on the class'
        def context = buildContext('''
package abstracttrait

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Inherited
@Around
class Shout extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Shout]

@Singleton
@InterceptorBean(Array(classOf[Shout]))
class ShoutInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    String.valueOf(context.proceed()) + "!"

trait Greeter:
  @Shout
  def greet(): String

@Singleton
class DefaultGreeter extends Greeter:
  override def greet(): String = "hello"
''', [:], true)
        def bean = getBean(context, 'abstracttrait.DefaultGreeter')

        then: 'the class is proxied because of an annotation it does not itself carry'
        bean instanceof Intercepted
        bean.greet() == 'hello!'

        cleanup:
        context?.close()
    }

    void "keeps an inherited annotation declared on an abstract trait method's parameter"() {
        when: '''the annotation is @Inherited, which is what decides whether a non-declared
                 annotation crosses at all -- a constraint like @NotBlank is not, so neither
                 language carries one here'''
        def definition = buildBeanDefinition('abstracttrait.DefaultChecker', '''
package abstracttrait

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.PARAMETER))
@Inherited
class Tagged extends StaticAnnotation

trait Checker:
  @Executable
  def check(@Tagged name: String): String

@Singleton
class DefaultChecker extends Checker:
  override def check(name: String): String = name
''')
        def method = definition.getRequiredMethod('check', String)

        then: 'the parameter of the implementing method carries what the trait declared'
        method.arguments[0].annotationMetadata.hasAnnotation('abstracttrait.Tagged')
    }

    void "does not produce a bean from a factory member declared only on a trait"() {
        when: '''@Bean is on the trait's abstract declaration and the factory overrides it'''
        def context = buildContext('''
package abstracttrait

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Named
import jakarta.inject.Singleton

class Widget(val name: String)

trait WidgetSource:
  @Bean
  @Named("special")
  def widget(): Widget

@Factory
@Singleton
class DefaultWidgetSource extends WidgetSource:
  override def widget(): Widget = new Widget("made")
''', [:], true)
        def widgetType = context.classLoader.loadClass('abstracttrait.Widget')

        then: '''no bean, and that is correct rather than a gap: `@Bean` is not `@Inherited`, so
                 it does not cross to the overriding member, in this language or in Java. Declaring
                 the producer concretely on the trait is the shape that works, and
                 `ScalaFactoryInheritanceParitySpec` covers it'''
        context.getBeanDefinitions(widgetType).isEmpty()

        cleanup:
        context?.close()
    }
}
