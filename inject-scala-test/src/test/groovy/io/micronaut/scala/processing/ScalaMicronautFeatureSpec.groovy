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
import io.micronaut.aop.InterceptedProxy
import io.micronaut.aop.HotSwappableInterceptedProxy
import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Prototype
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.context.exceptions.DependencyInjectionException
import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.http.annotation.Get
import io.micronaut.inject.ValidatedBeanDefinition
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.inject.writer.BeanDefinitionVisitor
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotatingVisitor
import jakarta.inject.Singleton

import java.util.function.Supplier

class ScalaMicronautFeatureSpec extends AbstractScalaTypeElementSpec {

    void "supports named qualifier constructor injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Named
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
@Named("v6")
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
@Named("v8")
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(@Named("v8") val engine: Engine)
''')

        then:
        getBean(context, 'example.Vehicle').engine.name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports array constructor injection"() {
        when:
        def source = '''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val engines: Array[Engine])
'''
        def definition = buildBeanDefinition('example.Vehicle', source)
        def context = buildContext(source)
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        definition.constructor.arguments.size() == 1
        vehicle.engines()*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala immutable List constructor injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val engines: scala.collection.immutable.List[Engine])
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.engines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "does not treat non-collection scala.collection types as collections of beans"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class Vehicle(val engines: scala.collection.Iterator[Engine])
''')
        getBean(context, 'example.Vehicle')

        then:
        // Iterator lives in scala.collection but is not a scala.collection.Iterable, so it is not
        // an injectable collection: it must resolve as a plain bean lookup, which finds nothing.
        def e = thrown(DependencyInjectionException)
        e.cause instanceof NoSuchBeanException
        e.message.contains('No bean of type [scala.collection.Iterator<example.Engine>] exists')

        cleanup:
        context?.close()
    }

    void "supports idiomatic Scala List constructor injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val engines: List[Engine])
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.engines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala Set and IndexedSeq constructor injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(
  val engineSet: scala.collection.immutable.Set[Engine],
  val indexedEngines: scala.collection.IndexedSeq[Engine]
)
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.engineSet())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.indexedEngines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala Seq method injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle:
  private var allEngines: scala.collection.Seq[Engine] = scala.collection.Seq.empty

  @Inject
  def setEngines(engines: scala.collection.Seq[Engine]): Unit =
    allEngines = engines

  def engines: scala.collection.Seq[Engine] = allEngines
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.engines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala mutable collection injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.collection.mutable

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(
    val constructorEngines: mutable.Buffer[Engine],
    val constructorIterableEngines: mutable.Iterable[Engine]
):
  private var allEngines: mutable.Seq[Engine] = mutable.Buffer.empty
  private var allIterableEngines: mutable.Iterable[Engine] = mutable.Buffer.empty

  @(Inject @field)
  var fieldEngines: mutable.Buffer[Engine] = mutable.Buffer.empty

  @(Inject @field)
  var fieldIterableEngines: mutable.Iterable[Engine] = mutable.Buffer.empty

  @Inject
  def setEngines(engines: mutable.Seq[Engine]): Unit =
    allEngines = engines

  @Inject
  def setIterableEngines(engines: mutable.Iterable[Engine]): Unit =
    allIterableEngines = engines

  def methodEngines: mutable.Seq[Engine] = allEngines

  def methodIterableEngines: mutable.Iterable[Engine] = allIterableEngines
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.constructorEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.constructorIterableEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.methodEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.methodIterableEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.fieldEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.fieldIterableEngines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala mutable Set injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.collection.mutable

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val constructorEngines: mutable.Set[Engine]):
  private var allEngines: mutable.Set[Engine] = mutable.Set.empty

  @(Inject @field)
  var fieldEngines: mutable.Set[Engine] = mutable.Set.empty

  @Inject
  def setEngines(engines: mutable.Set[Engine]): Unit =
    allEngines = engines

  def methodEngines: mutable.Set[Engine] = allEngines
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.constructorEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.methodEngines())*.name() as Set == ['v6', 'v8'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.fieldEngines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala Vector field injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle:
  @(Inject @field)
  var engines: scala.collection.immutable.Vector[Engine] = scala.collection.immutable.Vector.empty
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(vehicle.engines())*.name() as Set == ['v6', 'v8'] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala Map injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val constructorEngines: scala.collection.immutable.Map[String, Engine]):
  private var allEngines: scala.collection.Map[String, Engine] = scala.collection.Map.empty

  @(Inject @field)
  var fieldEngines: scala.collection.immutable.Map[String, Engine] = scala.collection.immutable.Map.empty

  @Inject
  def setEngines(engines: scala.collection.Map[String, Engine]): Unit =
    allEngines = engines

  def methodEngines: scala.collection.Map[String, Engine] = allEngines
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        vehicle.constructorEngines().apply('v6Engine').name() == 'v6'
        vehicle.constructorEngines().apply('v8Engine').name() == 'v8'
        vehicle.methodEngines().apply('v6Engine').name() == 'v6'
        vehicle.methodEngines().apply('v8Engine').name() == 'v8'
        vehicle.fieldEngines().apply('v6Engine').name() == 'v6'
        vehicle.fieldEngines().apply('v8Engine').name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports Scala mutable Map injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.collection.mutable

trait Engine:
  def name(): String

@Singleton
class V6Engine extends Engine:
  override def name(): String = "v6"

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val constructorEngines: mutable.Map[String, Engine]):
  private var allEngines: mutable.Map[String, Engine] = mutable.Map.empty

  @(Inject @field)
  var fieldEngines: mutable.Map[String, Engine] = mutable.Map.empty

  @Inject
  def setEngines(engines: mutable.Map[String, Engine]): Unit =
    allEngines = engines

  def methodEngines: mutable.Map[String, Engine] = allEngines
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        vehicle.constructorEngines().apply('v6Engine').name() == 'v6'
        vehicle.constructorEngines().apply('v8Engine').name() == 'v8'
        vehicle.methodEngines().apply('v6Engine').name() == 'v6'
        vehicle.methodEngines().apply('v8Engine').name() == 'v8'
        vehicle.fieldEngines().apply('v6Engine').name() == 'v6'
        vehicle.fieldEngines().apply('v8Engine').name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports Scala Option injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field

trait Engine:
  def name(): String

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val constructorEngine: Option[Engine]):
  private var allEngine: Option[Engine] = Option.empty

  @(Inject @field)
  var fieldEngine: Option[Engine] = Option.empty

  @Inject
  def setEngine(engine: Option[Engine]): Unit =
    allEngine = engine

  def methodEngine: Option[Engine] = allEngine
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        vehicle.constructorEngine().get().name() == 'v8'
        vehicle.methodEngine().get().name() == 'v8'
        vehicle.fieldEngine().get().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports empty Scala Option injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class Vehicle(val engine: Option[Engine])
''')
        def vehicle = getBean(context, 'example.Vehicle')

        then:
        vehicle.engine().isEmpty()

        cleanup:
        context?.close()
    }

    void "supports bean registration injection"() {
        when:
        def source = '''
package beanreg

import io.micronaut.context.BeanRegistration
import io.micronaut.context.annotation.Primary
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton
import scala.annotation.meta.field

@Singleton
class Test(
  val registrations: scala.collection.immutable.List[BeanRegistration[Foo]],
  val primaryBean: BeanRegistration[Foo],
  @Named("two") val secondaryBean: BeanRegistration[Foo]
):
  @(Inject @field)
  var fieldRegistrations: scala.collection.Seq[BeanRegistration[Foo]] = _

  @(Inject @field)
  var fieldArrayRegistrations: Array[BeanRegistration[Foo]] = _

  var methodRegistrations: scala.collection.immutable.Vector[BeanRegistration[Foo]] = _

  @Inject
  def setRegs(registrations: scala.collection.immutable.Vector[BeanRegistration[Foo]]): Unit =
    methodRegistrations = registrations

trait Foo

@Singleton
@Primary
class Foo1 extends Foo

@Singleton
@Named("two")
class Foo2 extends Foo
'''
        def context = buildContext(source)
        def bean = getBean(context, 'beanreg.Test')
        def registrations = scala.jdk.javaapi.CollectionConverters.asJavaCollection(bean.registrations())
        def fieldRegistrations = scala.jdk.javaapi.CollectionConverters.asJavaCollection(bean.fieldRegistrations())
        def methodRegistrations = scala.jdk.javaapi.CollectionConverters.asJavaCollection(bean.methodRegistrations())
        def fieldArrayRegistrations = bean.fieldArrayRegistrations().toList()

        then:
        bean.primaryBean().bean.getClass().name == 'beanreg.Foo1'
        bean.secondaryBean().bean.getClass().name == 'beanreg.Foo2'
        registrations.size() == 2
        fieldRegistrations.size() == 2
        fieldRegistrations as Set == registrations as Set
        fieldRegistrations as Set == methodRegistrations as Set
        fieldRegistrations as Set == fieldArrayRegistrations as Set
        registrations.any { it.bean.getClass().name == 'beanreg.Foo1' }
        registrations.any { it.bean.getClass().name == 'beanreg.Foo2' }

        cleanup:
        context?.close()
    }

    void "excludes abstract Scala beans from injected collections"() {
        when:
        def context = buildContext('''
package abstractbeans

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field

@Singleton
class Test:
  @(Inject @field)
  var rules: scala.collection.immutable.List[InterceptRule] = _

@Singleton
abstract class AbstractRule extends InterceptRule

@Singleton
class ConcreteRule extends InterceptRule

trait InterceptRule
''')
        def bean = getBean(context, 'abstractbeans.Test')

        then:
        def rules = scala.jdk.javaapi.CollectionConverters.asJavaCollection(bean.rules())
        rules.size() == 1
        rules.first().getClass().name == 'abstractbeans.ConcreteRule'

        cleanup:
        context?.close()
    }

    void "builds abstract Scala bean definitions with injection points"() {
        when:
        def source = '''
package abstractbeans

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.compiletime.uninitialized

@Singleton
abstract class AbstractBean:
  @(Inject @field)
  var host: String = uninitialized
'''
        def definition = buildBeanDefinition('abstractbeans.AbstractBean', source)

        then:
        definition != null
        definition.abstract
        definition.injectedMethods.size() == 1
    }

    void "creates Scala bean definitions for classes with only a qualifier"() {
        when:
        def definition = buildBeanDefinition('abstractbeans.QualifiedBean', '''
package abstractbeans

import jakarta.inject.Named

@Named("a")
class QualifiedBean
''')

        then:
        definition != null
        !definition.singleton
    }

    void "does not create Scala bean definitions for abstract classes with only a qualifier"() {
        when:
        def definition = buildBeanDefinition('abstractbeans.QualifiedBean', '''
package abstractbeans

import jakarta.inject.Named

@Named("a")
abstract class QualifiedBean
''')

        then:
        definition == null
    }

    void "creates Scala bean definitions for classes with only AOP advice"() {
        when:
        def definition = buildBeanDefinition('abstractbeans.ValidatedBean', '''
package abstractbeans

import io.micronaut.validation.Validated

@Validated
class ValidatedBean
''')

        then:
        definition != null
    }

    void "does not create Scala bean definitions for abstract classes with only AOP advice"() {
        when:
        def definition = buildBeanDefinition('abstractbeans.ValidatedBean', '''
package abstractbeans

import io.micronaut.validation.Validated

@Validated
abstract class ValidatedBean
''')

        then:
        definition == null
    }

    void "supports source-defined proxy target around advice"() {
        when:
        def context = buildContext('''
package proxytarget

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around(proxyTarget = true)
class Mutating extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Mutating]

@Singleton
@InterceptorBean(Array(classOf[Mutating]))
class MutatingInterceptor extends MethodInterceptor[Object, Object]:
  var invoked: Boolean = false

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    invoked = true
    context.proceed()

@Singleton
@Mutating
class MyBean:
  var count: Int = 0

  @PostConstruct
  def created(): Unit =
    count = count + 1

  def someMethod(): String = "good"
''')
        def instance = getBean(context, 'proxytarget.MyBean')
        def interceptor = getBean(context, 'proxytarget.MutatingInterceptor')

        then:
        instance instanceof InterceptedProxy
        instance.someMethod() == 'good'
        interceptor.invoked()
        !instance.is(instance.interceptedTarget())
        instance.interceptedTarget().count() == 1

        cleanup:
        context?.close()
    }

    void "supports source-defined hotswappable proxy target around advice"() {
        when:
        def context = buildContext('''
package hotswap

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@Around(proxyTarget = true, hotswap = true)
class Mutating extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Mutating]

@Singleton
@InterceptorBean(Array(classOf[Mutating]))
class MutatingInterceptor extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    context.proceed()

@Singleton
@Mutating
class SwappableBean:
  var invocationCount: Int = 0

  def test(name: String): String =
    invocationCount = invocationCount + 1
    s"Name is $name"
''')
        def instance = getBean(context, 'hotswap.SwappableBean')
        def newInstance = context.classLoader.loadClass('hotswap.SwappableBean').getDeclaredConstructor().newInstance()

        then:
        instance instanceof HotSwappableInterceptedProxy
        instance.interceptedTarget().getClass().name == 'hotswap.SwappableBean'
        instance.test('test') == 'Name is test'
        instance.interceptedTarget().invocationCount() == 1

        when:
        instance.swap(newInstance)

        then:
        instance.interceptedTarget().is(newInstance)
        instance.interceptedTarget().invocationCount() == 0

        cleanup:
        context?.close()
    }

    void "supports requires conditions on Scala beans"() {
        when:
        def disabled = buildContext('''
package example

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(property = "feature.enabled", value = "true")
class FeatureBean
''')
        def enabled = buildContext('''
package example

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(property = "feature.enabled", value = "true")
class FeatureBean
''', ['feature.enabled': 'true'])

        then:
        !disabled.containsBean(disabled.classLoader.loadClass('example.FeatureBean'))
        enabled.containsBean(enabled.classLoader.loadClass('example.FeatureBean'))

        cleanup:
        disabled?.close()
        enabled?.close()
    }

    void "supports value constructor injection"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton

@Singleton
class Vehicle(@Value("${vehicle.name}") val name: String)
''', ['vehicle.name': 'roadster'])

        then:
        getBean(context, 'example.Vehicle').name() == 'roadster'

        cleanup:
        context?.close()
    }

    void "supports field and method injection"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Inject
import jakarta.inject.Singleton
import scala.annotation.meta.field

trait Engine:
  def name(): String

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Garage:
  @(Inject @field)
  var fieldEngine: Engine = _

  var methodEngine: Engine = _

  @Inject
  def install(engine: Engine): Unit =
    methodEngine = engine
''')
        def garage = getBean(context, 'example.Garage')

        then:
        garage.fieldEngine().name() == 'v8'
        garage.methodEngine().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports post construct lifecycle methods"() {
        when:
        def context = buildContext('''
package example

import jakarta.annotation.PostConstruct
import jakarta.inject.Singleton

@Singleton
class Worker:
  var started: Boolean = false

  @PostConstruct
  def init(): Unit =
    started = true
''')

        then:
        getBean(context, 'example.Worker').started()

        cleanup:
        context?.close()
    }

    void "supports pre destroy lifecycle methods"() {
        when:
        def context = buildContext('''
package example

import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton

@Singleton
class Worker:
  var stopped: Boolean = false

  @PreDestroy
  def stop(): Unit =
    stopped = true
''')
        def worker = getBean(context, 'example.Worker')

        then:
        !worker.stopped()

        when:
        context.close()

        then:
        worker.stopped()
    }

    void "supports inject scope dependencies"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.InjectScope
import jakarta.annotation.PreDestroy
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.ArrayList

trait Connection extends AutoCloseable:
  override def close(): Unit
  def isOpen(): Boolean

@Bean
class TestConnection(val other: Other) extends Connection:
  var open: Boolean = true

  override def isOpen(): Boolean = open && other.isOpen

  @PreDestroy
  override def close(): Unit =
    open = false

@Bean
class Other:
  var isOpen: Boolean = true

  @PreDestroy
  def close(): Unit =
    isOpen = false

@Singleton
class Test(@InjectScope conn1: Connection, @InjectScope conn2: Connection):
  val createdConnections = new ArrayList[Connection]()
  createdConnections.add(conn1)
  createdConnections.add(conn2)

  @Inject
  def init(@InjectScope conn3: Connection): Unit =
    createdConnections.add(conn3)
''')
        def bean = getBean(context, 'example.Test')
        def connections = bean.createdConnections()

        then:
        connections.size() == 3
        connections.every { !it.isOpen() }
        connections.every { !it.other().isOpen() }

        cleanup:
        context?.close()
    }

    void "supports simple factory methods"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

case class Engine(name: String)

@Factory
class EngineFactory:
  @Singleton
  def engine(): Engine = Engine("v8")
''')

        then:
        getBean(context, 'example.Engine').name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports factory val beans"() {
        when:
        def source = '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

case class Engine(name: String)

@Factory
class EngineFactory:
  @Bean
  @Singleton
  val engine: Engine = Engine("v8")
'''
        def element = buildClassElement('example.EngineFactory', source)
        def property = element.syntheticBeanProperties.find { it.name == 'engine' }
        def context = buildContext(source)

        then:
        property != null
        property.hasStereotype('jakarta.inject.Singleton')
        property.readMember.get().hasDeclaredStereotype('jakarta.inject.Scope')
        property.field.get().hasStereotype('jakarta.inject.Singleton')
        property.readMember.get().hasDeclaredStereotype('io.micronaut.context.annotation.Bean')
        getBean(context, 'example.Engine').name() == 'v8'

        cleanup:
        context?.close()
    }

    void "type element visitors can annotate Scala elements"() {
        when:
        def definition = ScalaAnnotatingVisitor.withAnnotations({
            buildBeanDefinition('example.TestListener', '''
package example

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Singleton
class TestListener:
  @Executable
  def receive(value: String): Unit = ()
''')
        } as Supplier)
        def receiveMethod = definition.findMethod('receive', String).get()
        def valueArgument = receiveMethod.arguments[0]

        then:
        definition.stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'class'
        receiveMethod.stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'method'
        valueArgument.annotationMetadata.stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'parameter'
    }

    void "type element visitor errors include fallback messages for exceptions without messages"() {
        when:
        ScalaAnnotatingVisitor.withClassFailure(new IllegalStateException(), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error processing Scala element [example.Engine]: java.lang.IllegalStateException')
    }

    void "type element visitor processing exceptions include fallback messages"() {
        when:
        ScalaAnnotatingVisitor.withClassFailure(new ProcessingException(null, null), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error processing Scala element [example.Engine]: io.micronaut.inject.processing.ProcessingException')
    }

    void "type element visitor start processing exceptions are reported as errors"() {
        when:
        ScalaAnnotatingVisitor.withStartFailure(new ProcessingException(null, null), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error processing Scala element: io.micronaut.inject.processing.ProcessingException')
    }

    void "type element visitor start exceptions are reported as processing errors"() {
        when:
        ScalaAnnotatingVisitor.withStartFailure(new IllegalStateException(), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error initializing type visitor')
        e.message.contains('java.lang.IllegalStateException')
    }

    void "type element visitor finish processing exceptions include fallback messages"() {
        when:
        ScalaAnnotatingVisitor.withFinishFailure(new ProcessingException(null, null), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error processing Scala element: io.micronaut.inject.processing.ProcessingException')
    }

    void "type element visitor finish exceptions are reported as processing errors"() {
        when:
        ScalaAnnotatingVisitor.withFinishFailure(new IllegalStateException(), {
            buildClassElement('example.Engine', '''
package example

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error finalizing type visitor')
        e.message.contains('java.lang.IllegalStateException')
    }

    void "type element visitors can annotate Scala introduction methods"() {
        when:
        def definition = ScalaAnnotatingVisitor.withAnnotations({
            buildBeanDefinition('example.TextService' + BeanDefinitionVisitor.PROXY_SUFFIX, '''
package example

import io.micronaut.aop.Interceptor
import io.micronaut.aop.Introduction
import io.micronaut.aop.InvocationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Singleton
class StubIntroduction extends Interceptor[AnyRef, Object]:
  override def intercept(context: InvocationContext[AnyRef, Object]): Object =
    context.proceed()

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation

@Stub
trait TextService:
  @Executable
  def save(name: String, age: Int): Unit
  @Executable
  def saveTwo(name: String): Unit
''')
        } as Supplier)

        then:
        !definition.abstract
        definition.getRequiredMethod('save', String, Integer.TYPE)
            .stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'method'
        definition.getRequiredMethod('saveTwo', String)
            .stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'method'
    }

    void "type element visitor annotation mutations expand Scala annotation stereotypes"() {
        when:
        def element = ScalaAnnotatingVisitor.withClassAnnotation('example.MySingleton', {
            buildClassElement('example.Engine', '''
package example

import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Singleton
class MySingleton extends StaticAnnotation

@MySingleton
class Registered

class Engine
''')
        } as Supplier)

        then:
        element.hasAnnotation('example.MySingleton')
        element.hasStereotype(Singleton)
    }

    void "type element visitors can annotate Scala introspection properties"() {
        when:
        def introspection = ScalaAnnotatingVisitor.withAnnotations({
            buildBeanIntrospection('example.Test', '''
package example

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(var name: String)
''')
        } as Supplier)

        then:
        introspection.getRequiredProperty('name', String)
            .stringValue(ScalaAnnotatingVisitor.ANN, 'target').get() == 'property'
    }

    void "supports source-defined annotation aliases on Scala annotation members"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.AliasFor
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import jakarta.inject.Named
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Singleton
@Executable
class TestAnnotation(
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val value: String = ""
) extends StaticAnnotation

@Factory
class Test:
  @TestAnnotation("foo")
  def myFunc(): java.util.function.Function[String, java.lang.Integer] =
    (value: String) => java.lang.Integer.valueOf(10)
''', true)
        def definition = context.getBeanDefinition(java.util.function.Function, Qualifiers.byName('foo'))

        then:
        definition.getValue('jakarta.inject.Named', String).get() == 'foo'
        context.getBean(java.util.function.Function, Qualifiers.byName('foo')).apply('test') == 10

        cleanup:
        context?.close()
    }

    void "supports executable methods"() {
        when:
        def definition = buildBeanDefinition('example.Calculator', '''
package example

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Singleton
class Calculator:
  @Executable
  def add(left: Int, right: Int): Int = left + right
''')

        then:
        definition.findMethod('add', Integer.TYPE, Integer.TYPE).present
    }

    void "preserves empty array annotation members on executable methods"() {
        when:
        def definition = buildBeanDefinition('example.TaskController', '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.http.annotation.Get

@Bean
class TaskController:
  @Get(uri = "/tasks", produces = Array("application/json"), consumes = Array())
  def tasks(): String = "[]"
''')
        def method = definition.executableMethods.iterator().next()

        then:
        method.stringValues(Get, 'produces') == ['application/json'] as String[]
        method.stringValues(Get, 'consumes') == new String[0]
        method.getAnnotation(Get).values.get('consumes') == new String[0]
    }

    void "supports routes inherited from Scala traits"() {
        given:
        def definition = buildBeanDefinition('test.HelloController', '''
package test

import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Produces

@Controller("/hello")
class HelloController extends ControllerTrait:
  @Get("/{x}")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def index(x: String): String = s"Hello World $x"

trait ControllerTrait:
  @Get("/trait/{x}")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def indexT(x: String): String = s"Hello World Trait $x"
''')

        expect:
        definition != null
        definition.executableMethods.size() == 2
        definition.executableMethods.find { it.methodName == 'indexT' }
        definition.executableMethods.find { it.methodName == 'indexT' }.arguments.length == 1
        definition.executableMethods.find { it.methodName == 'indexT' }.isAnnotationPresent('io.micronaut.http.annotation.Get')
        definition.executableMethods.find { it.methodName == 'indexT' }.arguments[0].name == 'x'
    }

    void "supports around advice on Scala methods"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.retry.annotation.Retryable
import jakarta.inject.Singleton

@Singleton
class FlakyService:
  var attempts: Int = 0

  @Retryable(attempts = "2", delay = "1ms")
  def call(): String =
    attempts = attempts + 1
    if attempts == 1 then throw RuntimeException("boom")
    "ok"
''', true)
        def service = getBean(context, 'example.FlakyService')

        then:
        service.call() == 'ok'
        service.attempts() == 2

        cleanup:
        context?.close()
    }

    void "supports introduction advice on Scala traits"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.aop.Interceptor
import io.micronaut.aop.Introduction
import io.micronaut.aop.InvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Singleton
class StubIntroduction extends Interceptor[AnyRef, Object]:
  var invoked: Int = 0

  override def intercept(context: InvocationContext[AnyRef, Object]): Object =
    invoked = invoked + 1
    Integer.valueOf(context.getParameterValues()(0).asInstanceOf[String].length)

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation

@Stub
trait TextService:
  def length(value: String): Int
''')
        def service = getBean(context, 'example.TextService')
        def interceptor = getBean(context, 'example.StubIntroduction')

        then:
        service instanceof Intercepted
        service.length('test') == 4
        interceptor.invoked() == 1

        cleanup:
        context?.close()
    }

    void "exposes inherited Java interfaces for Scala interceptor beans"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton

@Singleton
class TimingInterceptor extends MethodInterceptor[AnyRef, Object]:
  override def intercept(context: MethodInvocationContext[AnyRef, Object]): Object =
    context.proceed()
''')

        then:
        context.getBean(io.micronaut.aop.Interceptor)

        cleanup:
        context?.close()
    }

    void "supports inherited source-defined singleton stereotypes"() {
        when:
        def context = buildContext('''
package example

import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Inherited
@Singleton
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class InheritedSingleton extends StaticAnnotation

@Singleton
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class LocalSingleton extends StaticAnnotation

@InheritedSingleton
class Machine

@LocalSingleton
class LocalMachine

class Engine extends Machine

class LocalEngine extends LocalMachine
''')
        def engineType = context.classLoader.loadClass('example.Engine')
        def localEngineType = context.classLoader.loadClass('example.LocalEngine')

        then:
        context.containsBean(engineType)
        !context.containsBean(localEngineType)

        cleanup:
        context?.close()
    }

    void "supports inherited classpath singleton stereotypes"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.scala.processing.fixtures.ExternalLocalMachine
import io.micronaut.scala.processing.fixtures.ExternalMachine

class Engine extends ExternalMachine

class LocalEngine extends ExternalLocalMachine
''')
        def engineType = context.classLoader.loadClass('example.Engine')
        def localEngineType = context.classLoader.loadClass('example.LocalEngine')

        then:
        context.containsBean(engineType)
        !context.containsBean(localEngineType)

        cleanup:
        context?.close()
    }

    void "rejects singleton Scala enum beans"() {
        when:
        buildBeanDefinition('example.Status', '''
package example

import jakarta.inject.Singleton

@Singleton
enum Status:
  case Active
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Enum types cannot be defined as beans')
    }

    void "supports source-defined default scopes"() {
        when:
        def definition = buildBeanDefinition('example.Engine', '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.DefaultScope
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Bean
@DefaultScope(classOf[Singleton])
class DefaultSingleton extends StaticAnnotation

@DefaultSingleton
class Engine
''')

        then:
        definition.isSingleton()
        definition.hasDeclaredStereotype(Singleton)
    }

    void "explicit Scala bean scope overrides source-defined default scope"() {
        when:
        def definition = buildBeanDefinition('example.Engine', '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.DefaultScope
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Bean
@DefaultScope(classOf[Singleton])
class DefaultSingleton extends StaticAnnotation

@DefaultSingleton
@Prototype
class Engine
''')

        then:
        !definition.isSingleton()
        !definition.hasDeclaredStereotype(Singleton)
        definition.hasDeclaredStereotype(Prototype)
        definition.scopeName.get() == Prototype.NAME
    }

    void "explicit Scala factory scope overrides source-defined default scope"() {
        when:
        def definition = buildBeanDefinition('example.MyBeanFactory', '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.DefaultScope
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Bean
@DefaultScope(classOf[Singleton])
class DefaultSingleton extends StaticAnnotation

@Factory
@Prototype
class MyBeanFactory:
  @DefaultSingleton
  @Prototype
  def myBean(): MyBean = MyBean()

class MyBean
''')

        then:
        !definition.isSingleton()
        definition.scopeName.get() == Prototype.NAME
    }

    void "explicit Scala factory method scope overrides source-defined default scope"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.DefaultScope
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Prototype
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Bean
@DefaultScope(classOf[Singleton])
class DefaultSingleton extends StaticAnnotation

@Factory
class MyBeanFactory:
  @DefaultSingleton
  @Prototype
  def myBean(): MyBean = MyBean()

class MyBean
''')

        then:
        !getBeanDefinition(context, 'example.MyBean').isSingleton()

        cleanup:
        context?.close()
    }

    void "Scala bean factory method without explicit scope remains unscoped"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory

@Factory
class MyBeanFactory:
  @Bean
  def myBean(): MyBean = MyBean()

class MyBean
''')

        then:
        !getBeanDefinition(context, 'example.MyBean').isSingleton()

        cleanup:
        context?.close()
    }

    void "supports mutable configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
class AppConfig:
  var name: String = _
  var port: Int = 0
''', ['app.name': 'demo', 'app.port': 8080], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.name() == 'demo'
        config.port() == 8080

        cleanup:
        context?.close()
    }

    void "supports immutable case class configuration properties"() {
        when:
        def source = '''
package example

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
case class AppConfig(name: String, port: Int)
'''
        def element = buildClassElement('example.AppConfig', source)
        def constructor = element.primaryConstructor.get()
        def definition = buildBeanDefinition('example.AppConfig', source)
        def arguments = definition.constructor.arguments

        then:
        constructor.hasAnnotation(ConfigurationInject)
        constructor.parameters[0].stringValue(Property, 'name').get() == 'app.name'
        constructor.parameters[1].stringValue(Property, 'name').get() == 'app.port'
        arguments[0].annotationMetadata.stringValue(Property, 'name').get() == 'app.name'
        arguments[1].annotationMetadata.stringValue(Property, 'name').get() == 'app.port'

        when:
        def context = buildContext(source, ['app.name': 'demo', 'app.port': 8080], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.name() == 'demo'
        config.port() == 8080

        cleanup:
        context?.close()
    }

    void "supports Scala collection configuration properties"() {
        when:
        def source = '''
package example

import io.micronaut.context.annotation.ConfigurationProperties
import scala.collection.mutable

@ConfigurationProperties("app")
case class AppConfig(
  names: scala.collection.immutable.List[String],
  labels: scala.collection.immutable.Map[String, String],
  mutableNames: mutable.Buffer[String],
  mutableTags: mutable.Set[String],
  mutableLabels: mutable.Map[String, String]
)
'''
        def context = buildContext(source, [
                'app.names[0]'        : 'alpha',
                'app.names[1]'        : 'beta',
                'app.labels.one'      : 'first',
                'app.labels.two'      : 'second',
                'app.mutable-names[0]': 'gamma',
                'app.mutable-names[1]': 'delta',
                'app.mutable-tags[0]' : 'fast',
                'app.mutable-tags[1]' : 'blue',
                'app.mutable-labels.x': 'ex',
                'app.mutable-labels.y': 'why'
        ], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(config.names()).toList() == ['alpha', 'beta']
        config.labels().apply('one') == 'first'
        config.labels().apply('two') == 'second'
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(config.mutableNames()).toList() == ['gamma', 'delta']
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(config.mutableTags()) as Set == ['fast', 'blue'] as Set
        config.mutableLabels().apply('x') == 'ex'
        config.mutableLabels().apply('y') == 'why'

        cleanup:
        context?.close()
    }

    void "supports primitive and raw map Scala configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
class AppConfig:
  var enabled: Boolean = false
  var port: Int = 0
  var labels: java.util.Map[String, Object] = new java.util.LinkedHashMap[String, Object]()
''', [
                'app.enabled': true,
                'app.port': 8080,
                'app.labels.primary': 'yes',
                'app.labels.limit': 10
        ], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.enabled()
        config.port() == 8080
        config.labels().get('primary') == 'yes'
        config.labels().get('limit') == 10

        cleanup:
        context?.close()
    }

    void "supports configuration inject constructors with bean dependencies"() {
        when:
        def source = '''
package example

import io.micronaut.context.annotation.ConfigurationInject
import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.inject.Singleton

@Singleton
class Engine:
  def name(): String = "v8"

@ConfigurationProperties("app")
class AppConfig @ConfigurationInject (val name: String, val engine: Engine)
'''
        def definition = buildBeanDefinition('example.AppConfig', source)
        def arguments = definition.constructor.arguments

        then:
        arguments[0].annotationMetadata.stringValue(Property, 'name').get() == 'app.name'
        !arguments[1].annotationMetadata.hasAnnotation(Property)

        when:
        def context = buildContext(source, ['app.name': 'demo'], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.name() == 'demo'
        config.engine().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports validation on mutable configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.validation.constraints.Min
import scala.annotation.meta.field

@ConfigurationProperties("app")
class AppConfig:
  @(Min @field)(value = 1L)
  var port: Int = 0
''', ['app.port': 0], true)
        def configType = context.classLoader.loadClass('example.AppConfig')
        def definition = context.getBeanDefinition(configType)

        then:
        definition instanceof ValidatedBeanDefinition

        when:
        context.getBean(configType)

        then:
        thrown(BeanInstantiationException)

        cleanup:
        context?.close()
    }

    void "supports cascaded validation on nested Scala configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import scala.annotation.meta.field

@ConfigurationProperties("app")
class AppConfig:
  @(Valid @field)
  var engine: EngineConfig = EngineConfig()

class EngineConfig:
  @(Min @field)(value = 1L)
  var cylinders: Int = 0
''', ['app.engine.cylinders': 0], true)
        def configType = context.classLoader.loadClass('example.AppConfig')
        def definition = context.getBeanDefinition(configType)

        then:
        definition instanceof ValidatedBeanDefinition

        when:
        context.getBean(configType)

        then:
        thrown(BeanInstantiationException)

        cleanup:
        context?.close()
    }

    void "supports nested configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
class AppConfig:
  var name: String = _
  var engine: AppConfig.EngineConfig = AppConfig.EngineConfig()

object AppConfig:
  @ConfigurationProperties("engine")
  class EngineConfig:
    var cylinders: Int = 0
''', ['app.name': 'demo', 'app.engine.cylinders': 6], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.name() == 'demo'
        config.engine().cylinders() == 6

        cleanup:
        context?.close()
    }

    void "supports factory-backed Scala configuration properties"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Factory

@Factory
class AppConfigFactory:
  @Bean
  @ConfigurationProperties("factory.app")
  def appConfig(): AppConfig = AppConfig()

class AppConfig:
  var name: String = _
  var port: Int = 0
''', ['factory.app.name': 'demo', 'factory.app.port': 8080], true)
        def config = getBean(context, 'example.AppConfig')

        then:
        config.name() == 'demo'
        config.port() == 8080

        cleanup:
        context?.close()
    }

    void "supports each property configuration beans"() {
        when:
        def context = buildContext('''
package example

import io.micronaut.context.annotation.EachProperty

@EachProperty("engines")
class EngineConfig:
  var cylinders: Int = 0
''', [
            'engines.small.cylinders': 6,
            'engines.large.cylinders': 8
        ], true)
        assert context.environment.containsProperties('engines.large')
        assert context.environment.getPropertyEntries('engines').containsAll(['small', 'large'])
        def engineType = context.classLoader.loadClass('example.EngineConfig')
        def engines = context.getBeansOfType(engineType)
        def large = getBean(context, 'example.EngineConfig', Qualifiers.byName('large'))

        then:
        engines.size() == 2
        large.cylinders() == 8

        cleanup:
        context?.close()
    }
}
