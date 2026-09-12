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
import io.micronaut.inject.BeanDefinition
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler

import java.nio.file.Path

/**
 * A bean compiled against a library compiled earlier behaves as it does when the two are
 * compiled together.
 *
 * <p>The element-level parity in {@link ScalaClasspathParitySpec} says the two models agree;
 * this is the same question asked of the running application, one bean feature at a time,
 * with the feature's declaration always on the far side of the boundary: the injected field
 * and lifecycle method on a library base class, the property on a library configuration
 * class, the executable and advised methods on a library trait, the advice annotation and
 * its interceptor in the library. This is the shape of every incremental build and of every
 * multi-module project, and the one the clean build never exercises.</p>
 */
class ScalaIncrementalBuildSpec extends AbstractScalaTypeElementSpec {

    private static final String LIBRARY = '''
package library

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Executable
import jakarta.annotation.PostConstruct
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Singleton
class Clock:
  def now(): Long = 42L

/** State to inject and a lifecycle method, both to be found on a subclass compiled later. */
abstract class Component:
  @Inject var clock: Clock = null
  var started: Boolean = false

  @PostConstruct
  def start(): Unit = started = true

  def describe: String

@ConfigurationProperties("parent")
class ParentConfig:
  var shared: String = ""

/** An executable method a subclass compiled later inherits. */
trait Greeter:
  @Executable
  def greet(name: String): String = "hello " + name

/** Generic methods whose types a subclass compiled later binds. */
trait Repository[E, ID]:
  @Executable
  def find(id: ID): E

  @Executable
  def save(entity: E): E = entity

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.METHOD, ElementType.TYPE))
@Around
class Counted extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] = classOf[Counted]

@Singleton
@InterceptorBean(Array(classOf[Counted]))
class CountedInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Int = 0

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = invoked + 1
    context.proceed()

/** An advised method a subclass compiled later inherits. */
trait Worker:
  @Counted
  def work(name: String): String = "work-" + name

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
@Around
@Introduction
class Stub extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] = classOf[Stub]

@Singleton
@InterceptorBean(Array(classOf[Stub]))
class StubInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    "stub:" + context.getMethodName
'''

    private static Path libraryCache

    private Path library() {
        if (libraryCache == null) {
            libraryCache = precompile(ScalaCompiler.SourceFile.scala('library.Library', LIBRARY))
        }
        libraryCache
    }

    void "a field and a lifecycle method declared on a library base class are honoured"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import jakarta.inject.Singleton

@Singleton
class Service extends library.Component:
  def describe: String = "service"
''')

        when:
        def service = getBean(context, 'test.Service')

        then: 'the inherited @Inject field is set'
        service.clock() != null
        service.clock().now() == 42L

        and: 'the inherited @PostConstruct method has run'
        service.started()

        cleanup:
        context?.close()
    }

    void "a property inherited from a library configuration class binds under that class's prefix"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("child")
class ChildConfig extends library.ParentConfig:
  var own: String = ""
''', ['parent.shared': 'from-parent', 'parent.child.shared': 'from-child', 'parent.child.own': 'own-value'])

        when:
        def child = getBean(context, 'test.ChildConfig')

        then: 'the subclass prefix nests under the library prefix'
        child.own() == 'own-value'

        and: 'the inherited property keeps the declaring class prefix, as it does from source'
        child.shared() == 'from-parent'

        cleanup:
        context?.close()
    }

    void "an executable method inherited from a library trait is executable on the bean"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import jakarta.inject.Singleton

@Singleton
class EnglishGreeter extends library.Greeter
''')
        BeanDefinition<?> definition = getBeanDefinition(context, 'test.EnglishGreeter')

        when:
        def greet = definition.findMethod('greet', String).get()

        then:
        greet.invoke(getBean(context, 'test.EnglishGreeter'), 'world') == 'hello world'

        cleanup:
        context?.close()
    }

    void "a generic method inherited from a library trait has the subclass's type arguments"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import jakarta.inject.Singleton

case class Book(title: String)

@Singleton
class BookRepository extends library.Repository[Book, Long]:
  def find(id: Long): Book = Book("book-" + id)
''')
        BeanDefinition<?> definition = getBeanDefinition(context, 'test.BookRepository')

        when:
        def find = definition.findMethod('find', Long).get()
        def save = definition.executableMethods.find { it.methodName == 'save' }

        then: 'the argument and return types are the bound ones, not the erased Object'
        find.arguments[0].type == Long
        find.returnType.type.name == 'test.Book'
        save.arguments[0].type.name == 'test.Book'
        save.returnType.type.name == 'test.Book'

        and: 'and the methods run'
        find.invoke(getBean(context, 'test.BookRepository'), 7L).title() == 'book-7'

        cleanup:
        context?.close()
    }

    void "around advice declared on a library trait method applies, through the library's own interceptor"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import jakarta.inject.Singleton

@Singleton
class DefaultWorker extends library.Worker
''')

        when:
        def worker = getBean(context, 'test.DefaultWorker')
        def interceptor = getBean(context, 'library.CountedInterceptor')

        then:
        worker instanceof Intercepted
        worker.work('one') == 'work-one'
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "an advice annotation from the library applies to a method declared now"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import jakarta.inject.Singleton
import library.Counted

@Singleton
class Counter:
  @Counted
  def count(): Int = 1
''')

        when:
        def counter = getBean(context, 'test.Counter')
        def interceptor = getBean(context, 'library.CountedInterceptor')

        then:
        counter instanceof Intercepted
        counter.count() == 1
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "introduction advice from the library implements an abstract type declared now"() {
        given:
        def context = buildContextAgainst([library()], '''
package test

import library.Stub

@Stub
trait Remote:
  def call(): String
  def other(input: String): String
''')

        when:
        def remote = getBean(context, 'test.Remote')

        then:
        remote instanceof Intercepted
        remote.call() == 'stub:call'
        remote.other('x') == 'stub:other'

        cleanup:
        context?.close()
    }
}
