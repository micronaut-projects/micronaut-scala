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

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * The Scala counterpart of Core's {@code PrivateInjectConstructorSpec} (Java, Kotlin and Groovy)
 * and of the {@code @Import} case in its {@code BeanImportSpec}: a private constructor annotated
 * {@code @Inject} or {@code @Creator} instantiates the bean reflectively when it opts in with
 * {@code @ReflectiveAccess}, and is refused with the same message when it does not.
 */
class ScalaPrivateInjectConstructorSpec extends AbstractScalaTypeElementSpec {

    void 'a private @Inject constructor is invoked with reflection'() {
        given:
        def context = buildContext('''
package privatector.plain

import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.ReflectiveAccess
import jakarta.inject.Inject

@Prototype
class Service @Inject() @ReflectiveAccess() private ()
''')

        expect:
        getBean(context, 'privatector.plain.Service') != null

        cleanup:
        context.close()
    }

    void 'an unscoped bean with a private @Inject constructor is discovered'() {
        given:
        def context = buildContext('''
package privatector.unscoped

import io.micronaut.core.annotation.ReflectiveAccess
import jakarta.inject.Inject

class Service @Inject() @ReflectiveAccess() private ()
''')

        expect:
        getBean(context, 'privatector.unscoped.Service') != null

        cleanup:
        context.close()
    }

    void 'a private primary constructor with arguments and #annotations wins over a public secondary one'() {
        given:
        def context = buildContext("""
package privatector.args

import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Prototype
class Service $annotations private (val dependency: Dependency) {
  val origin: String = if (dependency == null) "public" else "injected"

  def this() = this(null)
}

@Singleton
class Dependency
""")

        when:
        def service = getBean(context, 'privatector.args.Service')

        then:
        service.origin() == 'injected'
        service.dependency().is(getBean(context, 'privatector.args.Dependency'))

        cleanup:
        context.close()

        where:
        annotations << [
            '@Inject() @io.micronaut.core.annotation.ReflectiveAccess()',
            '@io.micronaut.core.annotation.Creator() @io.micronaut.core.annotation.ReflectiveAccess()'
        ]
    }

    void 'a private secondary constructor with arguments and #annotations wins over the public primary one'() {
        given:
        def context = buildContext("""
package privatector.secondary

import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Prototype
class Service(val dependency: Dependency, val origin: String) {
  def this() = this(null, "public")

  $annotations
  private def this(dependency: Dependency) = this(dependency, "injected")
}

@Singleton
class Dependency
""")

        when:
        def service = getBean(context, 'privatector.secondary.Service')

        then:
        service.origin() == 'injected'
        service.dependency().is(getBean(context, 'privatector.secondary.Dependency'))

        cleanup:
        context.close()

        where:
        annotations << [
            '@Inject @io.micronaut.core.annotation.ReflectiveAccess',
            '@io.micronaut.core.annotation.Creator @io.micronaut.core.annotation.ReflectiveAccess'
        ]
    }

    void 'the only constructor of a bean is private'() {
        given:
        def context = buildContext('''
package privatector.sole

import io.micronaut.core.annotation.ReflectiveAccess
import jakarta.inject.Singleton

@Singleton
class Service @ReflectiveAccess() private ()
''')

        expect:
        getBean(context, 'privatector.sole.Service') != null

        cleanup:
        context.close()
    }

    void 'a private @Inject constructor with around construct advice'() {
        given:
        def context = buildContext('''
package privatector.aroundconstruct

import io.micronaut.aop.*
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.ReflectiveAccess
import jakarta.inject.*
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Prototype
@Constructed
class Service @Inject() @ReflectiveAccess() private ()

@Retention(RetentionPolicy.RUNTIME)
@InterceptorBinding(kind = InterceptorKind.AROUND_CONSTRUCT)
class Constructed extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Constructed]

@Singleton
@InterceptorBean(Array(classOf[Constructed]))
class ConstructedInterceptor extends ConstructorInterceptor[AnyRef] {
  var invocations: Int = 0

  override def intercept(context: ConstructorInvocationContext[AnyRef]): AnyRef = {
    invocations += 1
    context.proceed()
  }
}
''')

        when:
        def service = getBean(context, 'privatector.aroundconstruct.Service')

        then:
        service != null
        getBean(context, 'privatector.aroundconstruct.ConstructedInterceptor').invocations() == 1

        cleanup:
        context.close()
    }

    void 'a private @Inject constructor without @ReflectiveAccess fails compilation'() {
        when:
        buildBeanDefinition('privatector.noreflectiveaccess.Service', '''
package privatector.noreflectiveaccess

import io.micronaut.context.annotation.Prototype
import jakarta.inject.Inject

@Prototype
class Service @Inject() private ()
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Constructor is declared private and is not accessible for the instantiation. To instantiate the bean using reflection annotate the constructor with @ReflectiveAccess')
    }

    void 'around advice on a bean with a private @Inject constructor fails compilation'() {
        when:
        buildBeanDefinition('privatector.around.Service', '''
package privatector.around

import io.micronaut.aop.*
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.ReflectiveAccess
import jakarta.inject.*
import scala.annotation.StaticAnnotation

@Prototype
class Service @Inject() @ReflectiveAccess() private () {
  @Intercepting
  def hello(): String = "hello"
}

@Around
class Intercepting extends StaticAnnotation
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Cannot apply AOP advice to a bean created with a private constructor. The constructor must be made non-private to support proxying: privatector.around.Service')
    }

    void 'a Java bean imported with a private reflective constructor is instantiated'() {
        given:
        def context = buildContext('''
package beanimporttest3

import io.micronaut.context.annotation.Import
import io.micronaut.scala.processing.fixtures.ExternalPrivateInjectConstructorBean

@Import(classes = Array(classOf[ExternalPrivateInjectConstructorBean]))
class Application
''')

        expect:
        context.getBean(io.micronaut.scala.processing.fixtures.ExternalPrivateInjectConstructorBean) != null

        cleanup:
        context.close()
    }
}
