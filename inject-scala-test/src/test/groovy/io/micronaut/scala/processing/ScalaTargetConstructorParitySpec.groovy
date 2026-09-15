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
import io.micronaut.context.ApplicationContext
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * The Scala counterpart of Core's {@code AroundConstructTargetConstructorSpec}:
 * {@code BeanConstructor.getTargetConstructor()} resolves the {@link java.lang.reflect.Constructor}
 * a bean is instantiated with, and answers null when the bean is not created through a constructor
 * of its type -- which for Scala includes the idiomatic companion-object {@code @Creator}, surfaced
 * as a static method of the class.
 */
class ScalaTargetConstructorParitySpec extends AbstractScalaTypeElementSpec {

    void 'the target constructor of a plain around construct bean is its declared constructor'() {
        given:
        ApplicationContext context = buildContext('''
package targetctor.plain

import io.micronaut.aop.*
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.reflect.Constructor
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@AroundConstruct
class Tracked extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Tracked]

@Singleton
class Alpha

@Singleton
@Tracked
class MyBean(val alpha: Alpha)

object CapturingInterceptor {
  var first: Constructor[?] = null
  var second: Constructor[?] = null
}

@Singleton
@InterceptorBinding(value = classOf[Tracked], kind = InterceptorKind.AROUND_CONSTRUCT)
class CapturingInterceptor extends ConstructorInterceptor[AnyRef] {
  override def intercept(context: ConstructorInvocationContext[AnyRef]): AnyRef = {
    CapturingInterceptor.first = context.getConstructor.getTargetConstructor
    CapturingInterceptor.second = context.getConstructor.getTargetConstructor
    context.proceed()
  }
}
''')
        Class<?> beanType = context.classLoader.loadClass('targetctor.plain.MyBean')
        Class<?> alphaType = context.classLoader.loadClass('targetctor.plain.Alpha')
        Class<?> companion = context.classLoader.loadClass('targetctor.plain.CapturingInterceptor$')
        def module = companion.getField('MODULE$').get(null)

        when:
        def bean = context.getBean(beanType)

        then:
        !(bean instanceof Intercepted)
        module.first() == beanType.getDeclaredConstructor(alphaType)
        module.first().declaringClass == beanType

        and: 'the constructor is resolved once'
        module.second().is(module.first())

        cleanup:
        context.close()
    }

    void 'the target constructor of an introspection is its declared constructor'() {
        given:
        BeanIntrospection<?> introspection = buildBeanIntrospection('targetctor.introspection.MyBean', '''
package targetctor.introspection

import io.micronaut.core.annotation.Introspected

@Introspected
class MyBean(val name: String, val count: Int)
''')

        when:
        def constructor = introspection.getConstructor()

        then:
        constructor.getTargetConstructor() == introspection.beanType.getDeclaredConstructor(String, int)
        constructor.getTargetConstructor().is(constructor.getTargetConstructor())
    }

    void 'the target constructor of an introspection instantiating through a companion creator is null'() {
        given: 'a companion factory and a constructor with the same parameter types'
        BeanIntrospection<?> introspection = buildBeanIntrospection('targetctor.creator.MyBean', '''
package targetctor.creator

import io.micronaut.core.annotation.Creator
import io.micronaut.core.annotation.Introspected

@Introspected
class MyBean(val name: String)

object MyBean {
  @Creator
  def create(name: String): MyBean = new MyBean(name + "!")
}
''')

        when:
        def constructor = introspection.getConstructor()

        then: 'the creator instantiates the bean'
        introspection.instantiate('a').name() == 'a!'
        constructor.arguments*.type == [String]
        introspection.beanType.getDeclaredConstructor(String) != null

        and: 'so the constructor with the same signature is not the target'
        constructor.getTargetConstructor() == null
        constructor.getTargetConstructor() == null
    }

    void 'the target constructors of an introspection describing all constructors'() {
        given:
        BeanIntrospection<?> introspection = buildBeanIntrospection('targetctor.declared.MyBean', '''
package targetctor.declared

import io.micronaut.core.annotation.Creator
import io.micronaut.core.annotation.Introspected

@Introspected(constructors = true)
class MyBean(val name: String, val count: Int) {
  def this() = this("", 0)
}

object MyBean {
  @Creator
  def create(name: String, count: Int): MyBean = new MyBean(name, count)
}
''')
        Class<?> beanType = introspection.beanType

        when:
        def constructors = introspection.getConstructors()

        then: 'the instantiating creator first, then the declared constructors'
        constructors.size() == 3
        constructors[0].arguments*.type == [String, int]
        constructors[0].getTargetConstructor() == null
        constructors[1].getTargetConstructor() == beanType.getDeclaredConstructor(String, int)
        constructors[2].getTargetConstructor() == beanType.getDeclaredConstructor()
        constructors[1].getTargetConstructor().is(constructors[1].getTargetConstructor())

        and: 'the declared constructors instantiate through what they describe'
        constructors[1].instantiate('x', 2).count() == 2
    }

    void 'the target constructor of an enum introspection is null'() {
        given:
        BeanIntrospection<?> introspection = buildBeanIntrospection('targetctor.enums.Colour', '''
package targetctor.enums

import io.micronaut.core.annotation.Introspected

@Introspected
enum Colour extends java.lang.Enum[Colour]:
  case RED, GREEN
''')

        expect:
        introspection.getConstructor().getTargetConstructor() == null
    }
}
