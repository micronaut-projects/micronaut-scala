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

import io.micronaut.core.annotation.Order
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.type.Argument
import io.micronaut.core.type.GenericPlaceholder
import io.micronaut.core.type.TypeInformation
import io.micronaut.context.exceptions.BeanContextException
import io.micronaut.context.exceptions.DependencyInjectionException
import io.micronaut.context.exceptions.NonUniqueBeanException
import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import spock.lang.PendingFeature

class ScalaBeanDefinitionSpec extends AbstractScalaTypeElementSpec {

    void "formats Scala bean definition type strings"() {
        given:
        def definition = buildBeanDefinition('typestring.Test', '''
package typestring

import jakarta.inject.Singleton

@Singleton
class Test
''')

        expect:
        definition.asArgument().getTypeString(format) == result

        where:
        format                                    | result
        TypeInformation.TypeFormat.SIMPLE         | "Test"
        TypeInformation.TypeFormat.QUALIFIED      | "typestring.Test"
        TypeInformation.TypeFormat.SHORTENED      | "t.Test"
        TypeInformation.TypeFormat.ANSI_SIMPLE    | "\u001B[0;36mTest\u001B[0m"
        TypeInformation.TypeFormat.ANSI_QUALIFIED | "\u001B[0;36mtypestring.Test\u001B[0m"
        TypeInformation.TypeFormat.ANSI_SHORTENED | "\u001B[0;36mt.Test\u001B[0m"
    }

    void "limits Scala exposed bean types"() {
        given:
        def definition = buildBeanDefinition('limittypes.Test', '''
package limittypes

import io.micronaut.context.annotation.Bean
import jakarta.inject.Singleton

@Singleton
@Bean(typed = Array(classOf[Runnable]))
class Test extends Runnable:
  override def run(): Unit = ()
''')

        expect:
        definition.exposedTypes == [Runnable] as Set
    }

    void "fails compilation for invalid Scala exposed bean type"() {
        when:
        buildBeanDefinition('limittypes.Test', '''
package limittypes

import io.micronaut.context.annotation.Bean
import jakarta.inject.Singleton

@Singleton
@Bean(typed = Array(classOf[Runnable]))
class Test
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Bean defines an exposed type [java.lang.Runnable] that is not implemented by the bean type")
    }

    void "fails compilation for exposed Scala subclass bean type"() {
        when:
        buildBeanDefinition('limittypes.Test', '''
package limittypes

import io.micronaut.context.annotation.Bean
import jakarta.inject.Singleton

@Singleton
@Bean(typed = Array(classOf[X]))
class Test

class X extends Test
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Bean defines an exposed type [limittypes.X] that is not implemented by the bean type")
    }

    void "limits Scala factory exposed bean types"() {
        when:
        def context = buildContext('''
package limittypes

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

trait X
class Y extends X

@Factory
class TestFactory:
  @Singleton
  @Bean(typed = Array(classOf[X]))
  def method(): Y = Y()
''')

        then:
        getBean(context, 'limittypes.X') != null

        cleanup:
        context?.close()
    }

    void "fails compilation for invalid Scala factory exposed bean type"() {
        when:
        buildContext('''
package limittypes

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

trait Z
trait X
class Y extends X

@Factory
class TestFactory:
  @Singleton
  @Bean(typed = Array(classOf[Z]))
  def method(): X = Y()
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Bean defines an exposed type [limittypes.Z] that is not implemented by the bean type")
    }

    void "exposes Scala bean definition order metadata"() {
        given:
        def definition = buildBeanDefinition('test.TestOrder', '''
package test

import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Singleton
@Order(10)
class TestOrder
''')

        expect:
        definition.intValue(Order).getAsInt() == 10
    }

    void "exposes Scala nested bean definition order metadata"() {
        given:
        def definition = buildBeanDefinition('test.OuterBean$TestOrder', '''
package test

import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

object OuterBean:
  trait OrderedBean

  @Singleton
  @Order(10)
  class TestOrder extends OrderedBean
''')

        expect:
        definition.intValue(Order).getAsInt() == 10
    }

    void "builds Scala bean definition in package with uppercase letters"() {
        when:
        def definition = buildBeanDefinition('test.A.TestBean', '''
package test.A

import jakarta.inject.Singleton

@Singleton
class TestBean
''')

        then:
        noExceptionThrown()
        definition != null
    }

    void "passes Scala type arguments through parent hierarchy"() {
        given:
        def source = '''
package typearguments

import jakarta.inject.Singleton

@Singleton
class ChainA extends ChainB[java.lang.Boolean]

class ChainB[A] extends ChainC[A, Number, Integer]

abstract class ChainC[A, B, E] extends ChainD[A, B, String, E]

trait ChainD[A, B, C, E] extends ChainE[A, B, C, Byte]

trait ChainE[A, B, C, D]
'''
        def element = buildClassElement('typearguments.ChainA', source)
        def definition = buildBeanDefinition('typearguments.ChainA', source)

        expect:
        element.superType.get().typeArguments*.value*.name == ['java.lang.Boolean']
        element.getAllTypeArguments()["typearguments.ChainB"]*.value*.name == ['java.lang.Boolean']
        definition.getTypeArguments("typearguments.ChainB")*.type == [Boolean]
        definition.getTypeArguments("typearguments.ChainC")*.type == [Boolean, Number, Integer]
        definition.getTypeArguments("typearguments.ChainD")*.type == [Boolean, Number, String, Integer]
        definition.getTypeArguments("typearguments.ChainE")*.type == [Boolean, Number, String, Byte]
    }

    void "exposes declared Scala bean generics from definition"() {
        when:
        def definition = buildBeanDefinition('limittypes.Test', '''
package limittypes

import jakarta.inject.Singleton

@Singleton
class Test[K, V]
''')

        then:
        definition.getGenericBeanType().getTypeString(true) == 'Test<Object, Object>'
    }

    void "exposes declared Scala bean generics from reference"() {
        when:
        def reference = buildBeanDefinitionReference('limittypes.Test', '''
package limittypes

import jakarta.inject.Singleton

@Singleton
class Test[K, V]
''')

        then:
        reference.getGenericBeanType().getTypeString(true) == 'Test<Object, Object>'
    }

    void "exposes declared Scala bean generics from inherited reference"() {
        when:
        def reference = buildBeanDefinitionReference('test.DefaultKafkaConsumerConfiguration', '''
package test

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(beans = Array(classOf[KafkaDefaultConfiguration]))
class DefaultKafkaConsumerConfiguration[K, V] extends AbstractKafkaConsumerConfiguration[K, V]

abstract class AbstractKafkaConsumerConfiguration[K, V] extends AbstractKafkaConfiguration[K, V]

abstract class AbstractKafkaConfiguration[K, V]

class KafkaDefaultConfiguration
''')

        then:
        reference.getGenericBeanType().getTypeString(true) == 'DefaultKafkaConsumerConfiguration<Object, Object>'
    }

    void "exposes Scala factory bean generic type metadata"() {
        when:
        def context = buildContext('''
package limittypes

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class TestFactory:
  @Singleton
  def method(): X[Y] = Y()

trait X[T]

class Y extends X[Y]
''')
        def definition = getBeanDefinition(context, 'limittypes.X')

        then:
        definition.getGenericBeanType().getTypeString(true) == 'X<Y>'

        cleanup:
        context?.close()
    }

    void "supports Scala BeanProvider constructor injection"() {
        when:
        def context = buildContext('''
package providerinject

import io.micronaut.context.BeanProvider
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val beanProvider: BeanProvider[Engine])
''')
        def vehicle = getBean(context, 'providerinject.Vehicle')

        then:
        vehicle.beanProvider().get().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports Scala Provider constructor injection"() {
        when:
        def context = buildContext('''
package providerinject

import jakarta.inject.Provider
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class V8Engine extends Engine:
  override def name(): String = "v8"

@Singleton
class Vehicle(val provider: Provider[Engine])
''')
        def vehicle = getBean(context, 'providerinject.Vehicle')

        then:
        vehicle.provider().get().name() == 'v8'

        cleanup:
        context?.close()
    }

    void "supports Scala Replaces and abstract parent constructor injection"() {
        when:
        def context = buildContext('''
package replacement

import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

trait Engine:
  def name(): String

@Singleton
class DefaultEngine extends Engine:
  override def name(): String = "default"

@Singleton
@Replaces(classOf[DefaultEngine])
class ReplacementEngine extends Engine:
  override def name(): String = "replacement"

abstract class AbstractVehicle(val engine: Engine)

@Singleton
class Vehicle(engine: Engine) extends AbstractVehicle(engine)
''')
        def engineType = context.classLoader.loadClass('replacement.Engine')
        def engines = context.getBeansOfType(engineType)
        def vehicle = getBean(context, 'replacement.Vehicle')

        then:
        engines.size() == 1
        engines.first().name() == 'replacement'
        vehicle.engine().name() == 'replacement'

        cleanup:
        context?.close()
    }

    void "supports Scala factory val method and enum-returning beans"() {
        when:
        def context = buildContext('''
package factoryparity

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

case class Engine(name: String)

enum Status:
  case Active, Disabled

@Factory
class EngineFactory:
  @Bean
  @Singleton
  val fieldEngine: Engine = Engine("field")

  @Singleton
  def methodEngine(): Engine = Engine("method")

  @Singleton
  def status(): Status = Status.Active
''')
        def engineType = context.classLoader.loadClass('factoryparity.Engine')
        def statusType = context.classLoader.loadClass('factoryparity.Status')
        def engines = context.getBeansOfType(engineType)

        then:
        engines*.name() as Set == ['field', 'method'] as Set
        context.getBean(statusType).toString() == 'Active'

        cleanup:
        context?.close()
    }

    void "creates distinct Scala factory bean definitions for overloaded methods"() {
        given:
        def context = buildContext('''
package factoryparity

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

class CollisionBean

@Singleton
class DependencyOne

class DependencyTwo

@Factory
class CollisionFactory:
  @Singleton
  @Requires(beans = Array(classOf[DependencyOne]), missingBeans = Array(classOf[DependencyTwo]))
  def collision(dependency: DependencyOne): CollisionBean = CollisionBean()

  @Bean
  @Requires(beans = Array(classOf[DependencyOne], classOf[DependencyTwo]))
  def collision(dependency: DependencyOne, other: DependencyTwo): CollisionBean = CollisionBean()
''')
        def beanType = context.classLoader.loadClass('factoryparity.CollisionBean')

        when:
        def references = context.beanDefinitionReferences.findAll { it.beanType == beanType }

        then:
        references*.beanDefinitionName as Set == [
            'factoryparity.$CollisionFactory$Collision0$Definition',
            'factoryparity.$CollisionFactory$Collision1$Definition'
        ] as Set

        cleanup:
        context?.close()
    }

    void "supports Scala factories that return null or disable each bean outputs"() {
        when:
        def context = buildContext('''
package factoryparity

import io.micronaut.context.annotation.EachBean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Parameter
import io.micronaut.context.annotation.Prototype
import io.micronaut.context.exceptions.DisabledBeanException
import io.micronaut.core.annotation.Nullable
import jakarta.inject.Named
import jakarta.inject.Singleton

@Factory
class NullableFactory:
  var bCalls: Int = 0
  var cCalls: Int = 0
  var dCalls: Int = 0
  var d2Calls: Int = 0
  var d3Calls: Int = 0
  var d4Calls: Int = 0

  @Prototype
  def getA(@Parameter name: String): A = null

  @Singleton
  @Named("one")
  def getBOne(): B =
    bCalls += 1
    B("one")

  @Singleton
  @Named("two")
  def getBTwo(): B =
    bCalls += 1
    B("two")

  @Singleton
  @Named("three")
  def getBThree(): B =
    bCalls += 1
    B("three")

  @Singleton
  @Named("four")
  def getBFour(): B =
    bCalls += 1
    throw DisabledBeanException("Named four")

  @EachBean(classOf[B])
  def getC(b: B): C =
    cCalls += 1
    if b == null || b.name == "three" then
      throw DisabledBeanException("Named three")
    C(b.name)

  @EachBean(classOf[C])
  def getD(c: C): D =
    dCalls += 1
    if c == null || c.name == "two" then
      throw DisabledBeanException("Named two")
    D()

  @EachBean(classOf[C])
  def getD2(@Nullable c: C): D2 =
    d2Calls += 1
    if c == null then
      throw DisabledBeanException("Null C")
    if c.name == "two" then
      throw DisabledBeanException("Named two")
    D2()

  @EachBean(classOf[C])
  def getD3(@Parameter c: C): D3 =
    d3Calls += 1
    if c.name == "two" then
      throw DisabledBeanException("Named two")
    D3()

  @EachBean(classOf[C])
  def getD4(@Nullable c: C, @Nullable @Parameter b: B): D4 =
    d4Calls += 1
    D4()

  @EachBean(classOf[D])
  def getE(d: D, f: F): E = E()

  @Singleton
  def getF(): F = throw DisabledBeanException("Not active")

class A
case class B(name: String)
case class C(name: String)
case class D()
case class D2()
case class D3()
case class D4()
case class E()
case class F()
''')
        def factory = getBean(context, 'factoryparity.NullableFactory')
        def aType = context.classLoader.loadClass('factoryparity.A')
        def bType = context.classLoader.loadClass('factoryparity.B')
        def cType = context.classLoader.loadClass('factoryparity.C')
        def dType = context.classLoader.loadClass('factoryparity.D')
        def d2Type = context.classLoader.loadClass('factoryparity.D2')
        def d3Type = context.classLoader.loadClass('factoryparity.D3')
        def d4Type = context.classLoader.loadClass('factoryparity.D4')
        def eType = context.classLoader.loadClass('factoryparity.E')
        def fType = context.classLoader.loadClass('factoryparity.F')

        then:
        context.getBeansOfType(bType)*.name() as Set == ['one', 'two', 'three'] as Set
        context.getBeansOfType(cType)*.name() as Set == ['one', 'two'] as Set
        context.getBeansOfType(dType).size() == 1
        context.getBeansOfType(d2Type).size() == 1
        context.getBeansOfType(d3Type).size() == 1
        context.getBeansOfType(d4Type).size() == 4
        context.getBean(dType, Qualifiers.byName("one")) != null
        context.getBean(d2Type, Qualifiers.byName("one")) != null
        factory.dCalls() == 2
        factory.d2Calls() == 4
        factory.d3Calls() == 2
        factory.d4Calls() == 4

        when:
        context.createBean(aType, "hello")

        then:
        thrown(BeanContextException)

        when:
        context.getBean(eType, Qualifiers.byName("one"))

        then:
        thrown(NoSuchBeanException)

        when:
        context.getBean(fType)

        then:
        thrown(NoSuchBeanException)

        cleanup:
        context?.close()
    }

    @PendingFeature(reason = "Generated Java and Scala collection factory references are not yet returned as element-bean candidates")
    void "supports Scala factory methods producing collection elements"() {
        when:
        def context = buildContext('''
package factoryparity

import io.micronaut.context.BeanProvider
import io.micronaut.context.annotation.Any
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Qualifier
class All extends StaticAnnotation

@Qualifier
class WishList extends StaticAnnotation

case class Product(name: String)

@Singleton
class Catalogue(
  @All val all: java.util.List[Product],
  @WishList val wishlist: java.util.Set[Product],
  @WishList val scalaWishlist: scala.collection.immutable.List[Product],
  @Any val provider: BeanProvider[Product]
)

@Factory
class Shop:
  @Bean
  @All
  def getAllProducts(): java.util.List[Product] =
    java.util.List.of(Product("one"), Product("two"), Product("three"))

  @Bean
  @WishList
  def getWishList(): scala.collection.immutable.List[Product] =
    scala.collection.immutable.List(Product("four"), Product("five"))
''')
        def productClass = context.classLoader.loadClass('factoryparity.Product')
        def shopClass = context.classLoader.loadClass('factoryparity.Shop')
        def scalaListClass = context.classLoader.loadClass('scala.collection.immutable.List')
        def catalogue = getBean(context, 'factoryparity.Catalogue')

        then:
        context.getBeanDefinitions(shopClass).size() == 1
        context.getBeanDefinitions(shopClass, Qualifiers.byStereotype("factoryparity.WishList")).isEmpty()
        context.getBeanDefinitions(productClass).size() == 2
        catalogue.all()*.name() as Set == ['one', 'two', 'three'] as Set
        catalogue.wishlist()*.name() as Set == ['four', 'five'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(catalogue.scalaWishlist())*.name() as Set == ['four', 'five'] as Set
        catalogue.provider().stream().count() == 5
        context.containsBean(productClass)

        when:
        context.getBean(productClass)

        then:
        thrown(NonUniqueBeanException)

        when:
        context.getBean(productClass, Qualifiers.byStereotype("factoryparity.WishList"))

        then:
        thrown(NonUniqueBeanException)

        when:
        def allProducts = context.getBean(Argument.listOf(productClass), Qualifiers.byStereotype("factoryparity.All"))

        then:
        allProducts*.name() as Set == ['one', 'two', 'three'] as Set

        when:
        def wishlist = context.getBean(Argument.of(scalaListClass, Argument.of(productClass, "A")), Qualifiers.byStereotype("factoryparity.WishList"))

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(wishlist)*.name() as Set == ['four', 'five'] as Set

        cleanup:
        context?.close()
    }

    @PendingFeature(reason = "Generated Scala collection factory references are not yet returned as element-bean candidates")
    void "supports Scala singleton factory methods producing collection elements"() {
        when:
        def context = buildContext('''
package factoryparity

import io.micronaut.context.BeanProvider
import io.micronaut.context.annotation.Any
import io.micronaut.context.annotation.Factory
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Qualifier
class WishList extends StaticAnnotation

case class Product(name: String)

@Singleton
class Catalogue(
  @WishList val wishlist: java.util.Set[Product],
  @WishList val scalaWishlist: scala.collection.immutable.List[Product],
  @Any val provider: BeanProvider[Product]
)

@Factory
class Shop:
  @Singleton
  @WishList
  def getWishList(): scala.collection.immutable.List[Product] =
    scala.collection.immutable.List(Product("four"), Product("five"))
''')
        def productClass = context.classLoader.loadClass('factoryparity.Product')
        def scalaListClass = context.classLoader.loadClass('scala.collection.immutable.List')
        def catalogue = getBean(context, 'factoryparity.Catalogue')

        then:
        context.getBeanDefinitions(productClass).size() == 1
        catalogue.wishlist()*.name() as Set == ['four', 'five'] as Set
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(catalogue.scalaWishlist())*.name() as Set == ['four', 'five'] as Set
        catalogue.provider().stream().count() == 2

        when:
        def wishlist = context.getBean(Argument.of(scalaListClass, Argument.of(productClass, "A")), Qualifiers.byStereotype("factoryparity.WishList"))

        then:
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(wishlist)*.name() as Set == ['four', 'five'] as Set
        wishlist.is(context.getBean(Argument.of(scalaListClass, Argument.of(productClass, "A")), Qualifiers.byStereotype("factoryparity.WishList")))

        cleanup:
        context?.close()
    }

    void "exposes deep Scala constructor type parameters in bean definitions"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Singleton

@Singleton
class Test(val deepList: java.util.List[java.util.List[java.util.List[String]]])
''')

        expect:
        definition != null
        def constructor = definition.getConstructor()
        def param = constructor.getArguments()[0]
        param.getTypeParameters().length == 1
        def param1 = param.getTypeParameters()[0]
        param1.getTypeParameters().length == 1
        def param2 = param1.getTypeParameters()[0]
        param2.getTypeParameters().length == 1
        def param3 = param2.getTypeParameters()[0]
        param3.getType() == String.class
    }

    void "exposes annotation metadata on deep Scala constructor type parameters in bean definitions"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Singleton
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Singleton
class Test(
  val deepList: java.util.List[
    java.util.List[
      java.util.List[String @NotNull] @NotEmpty
    ] @Size(min = 1)
  ]
)
''')

        expect:
        definition != null
        def constructor = definition.getConstructor()
        def param = constructor.getArguments()[0]
        def param1 = param.getTypeParameters()[0]
        def param2 = param1.getTypeParameters()[0]
        def param3 = param2.getTypeParameters()[0]
        param1.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.Size$List')
        param2.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.NotEmpty$List')
        param3.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.NotNull$List')
        param.getAnnotationMetadata().getAnnotationNames().contains('io.micronaut.validation.annotation.ValidatedElement')
    }

    void "resolves Scala bean definition type variables for generic lookups"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.core.annotation.Order
import jakarta.inject.Singleton

@Singleton
class Test extends Serde[Object]

trait Serde[T] extends Serializer[T], Deserializer[T]

trait Serializer[T]

trait Deserializer[T]

@Singleton
@Order(-100)
class ArrayListTest[E] extends Serde[java.util.ArrayList[E]]

@Singleton
class SetTest[E] extends Serde[java.util.HashSet[E]]
''')
        def definition = getBeanDefinition(context, 'test.Test')

        when:
        def serdeTypeParam = definition.getTypeArguments("test.Serde")[0]
        def serializerTypeParam = definition.getTypeArguments("test.Serializer")[0]
        def deserializerTypeParam = definition.getTypeArguments("test.Deserializer")[0]
        def listDeserializer = context.getBean(Argument.of(context.classLoader.loadClass('test.Deserializer'), Argument.listOf(String)))
        def collectionDeserializer = context.getBean(Argument.of(context.classLoader.loadClass('test.Deserializer'), Argument.of(Collection.class, String)))

        then:
        listDeserializer.getClass().name == 'test.ArrayListTest'
        listDeserializer.is(collectionDeserializer)
        !serdeTypeParam.isTypeVariable()
        !(serdeTypeParam instanceof GenericPlaceholder)
        !serializerTypeParam.isTypeVariable()
        !(serializerTypeParam instanceof GenericPlaceholder)
        !deserializerTypeParam.isTypeVariable()
        !(deserializerTypeParam instanceof GenericPlaceholder)

        cleanup:
        context?.close()
    }

    void "resolves Scala array bean definition type variables for generic lookups"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Singleton

@Singleton
class Test extends Serde[Array[String]]

trait Serde[T] extends Serializer[T], Deserializer[T]

trait Serializer[T]

trait Deserializer[T]
''')

        when:
        def serdeTypeParam = definition.getTypeArguments("test.Serde")[0]
        def serializerTypeParam = definition.getTypeArguments("test.Serializer")[0]
        def deserializerTypeParam = definition.getTypeArguments("test.Deserializer")[0]

        then:
        serdeTypeParam.simpleName == "String[]"
        !serdeTypeParam.isTypeVariable()
        !(serdeTypeParam instanceof GenericPlaceholder)
        serializerTypeParam.simpleName == "String[]"
        !serializerTypeParam.isTypeVariable()
        !(serializerTypeParam instanceof GenericPlaceholder)
        deserializerTypeParam.simpleName == "String[]"
        !deserializerTypeParam.isTypeVariable()
        !(deserializerTypeParam instanceof GenericPlaceholder)
    }

    void "resolves Scala wildcard generic bounds without concrete type arguments"() {
        when:
        def definition = buildBeanDefinition('test.NumberThingManager', '''
package test

import jakarta.inject.Singleton

trait Thing[T]

trait NumberThing[T <: Number & Comparable[T]] extends Thing[T]

class AbstractThingManager[T <: Thing[?]]

@Singleton
class NumberThingManager extends AbstractThingManager[NumberThing[?]]
''')

        then:
        noExceptionThrown()
        definition != null
        definition.getTypeArguments("test.AbstractThingManager")[0].getTypeVariables().get("T").getType() == Number.class
    }

    void "resolves Scala wildcard generic upper bounds"() {
        when:
        def definition = buildBeanDefinition('test.NumberThingManager', '''
package test

import jakarta.inject.Singleton

trait Thing[T]

trait NumberThing[T <: Number & Comparable[T]] extends Thing[T]

class AbstractThingManager[T <: Thing[?]]

@Singleton
class NumberThingManager extends AbstractThingManager[NumberThing[? <: java.lang.Double]]
''')

        then:
        noExceptionThrown()
        definition != null
        definition.getTypeArguments("test.AbstractThingManager")[0].getTypeVariables().get("T").getType() == Double.class
    }

    void "exposes Scala named qualifier metadata"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Named

@Named("foo")
class Test
''')

        expect:
        definition.getDeclaredQualifier() == Qualifiers.byName("foo")
    }

    void "supports Scala implicit named qualifiers"() {
        given:
        def definition = buildBeanDefinition('implicitnamed.FooBar', '''
package implicitnamed

import jakarta.inject.Named

@Named
class FooBar
''')

        when:
        def context = buildContext('''
package implicitnamed

import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Primary
import jakarta.inject.Named
import jakarta.inject.Singleton

trait Foo:
  def name(): String

class NamedFoo(value: String) extends Foo:
  override def name(): String = value

@Factory
class TestFactory:
  @Singleton
  @Named
  def foo1(): Foo = NamedFoo("one")

  @Singleton
  @Primary
  @Named
  def fooPrimary(): Foo = NamedFoo("primary")

  @Singleton
  @Named
  def getFooGetter(): Foo = NamedFoo("getter")

@Singleton
class Test(
  @Named val foo1: Foo,
  @Named("foo1") val anotherFoo1: Foo,
  val fooDefault: Foo,
  @Named val fooPrimary: Foo,
  @Named("fooGetter") val getter: Foo
)
''')
        def bean = getBean(context, 'implicitnamed.Test')

        then:
        definition.stringValue(AnnotationUtil.NAMED).get() == 'fooBar'
        bean.foo1().name() == 'one'
        bean.anotherFoo1().name() == 'one'
        bean.fooDefault().name() == 'primary'
        bean.fooPrimary().name() == 'primary'
        bean.getter().name() == 'getter'

        cleanup:
        context?.close()
    }

    void "does not expose a qualifier for Scala scope-only beans"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Singleton

@Singleton
class Test
''')

        expect:
        definition.getDeclaredQualifier() == null
    }

    void "exposes Scala named qualifier metadata through source-defined alias"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import io.micronaut.context.annotation.AliasFor
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Named
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Bean
class MockBean(
  @(AliasFor @getter)(annotation = classOf[Replaces], member = "named")
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val named: String = ""
) extends StaticAnnotation

@MockBean(named = "foo")
class Test
''')

        expect:
        definition.getDeclaredQualifier() == Qualifiers.byName("foo")
        definition.getAnnotationNameByStereotype(AnnotationUtil.QUALIFIER).get() == AnnotationUtil.NAMED
    }

    void "exposes Scala qualifier annotation metadata"() {
        given:
        def definition = buildBeanDefinition('test.Test', '''
package test

import jakarta.inject.Qualifier
import scala.annotation.StaticAnnotation

@Qualifier
class MyQualifier extends StaticAnnotation

@MyQualifier
class Test
''')

        expect:
        definition.getDeclaredQualifier() == Qualifiers.byAnnotation(definition.getAnnotationMetadata(), "test.MyQualifier")
        definition.getAnnotationNameByStereotype(AnnotationUtil.QUALIFIER).get() == "test.MyQualifier"
    }

    void "supports Scala repeatable qualifier resolution"() {
        when:
        def context = buildContext('''
package repeatablequalifiers

import io.micronaut.context.BeanRegistration
import io.micronaut.context.annotation.Factory
import io.micronaut.scala.processing.fixtures.Location
import jakarta.inject.Named
import jakarta.inject.Singleton

@Singleton
class TestBean(
  @Location("north") @Location("south") val northSouth: Coordinate,
  @Location("north") val north: Coordinate,
  @Location("east") @Location("west") val eastWest: Coordinate
)

@Singleton
class TestBeanDef(
  @Location("north") @Location("south") val northSouth: BeanRegistration[Coordinate],
  @Named("south") @Location("south") val south: BeanRegistration[Coordinate],
  @Location("east") @Location("west") val eastWest: BeanRegistration[Coordinate]
)

@Singleton
class OtherBean(@Location("south") val south: Coordinate)

@Factory
class CoordinateFactory:
  @Singleton
  @Named("northSouth")
  @Location("north")
  @Location("south")
  def northSouth(): Coordinate = new Coordinate:
    override def name(): String = "North/South"

  @Singleton
  @Named("south")
  @Location("south")
  def south(): Coordinate = new Coordinate:
    override def name(): String = "South"

  @Singleton
  @Named("eastWest")
  @Location("east")
  @Location("west")
  def eastWest(): Coordinate = new Coordinate:
    override def name(): String = "East/West"

trait Coordinate:
  def name(): String
''')
        def bean = getBean(context, 'repeatablequalifiers.TestBean')

        then:
        bean.northSouth.name() == 'North/South'
        bean.north.name() == 'North/South'
        bean.eastWest.name() == 'East/West'

        when:
        getBean(context, 'repeatablequalifiers.OtherBean')

        then:
        def e = thrown(DependencyInjectionException)
        e.cause instanceof NonUniqueBeanException

        when:
        def beanDef = getBean(context, 'repeatablequalifiers.TestBeanDef')
        def eastWest = beanDef.eastWest.beanDefinition
        def northSouth = beanDef.northSouth.beanDefinition
        def south = beanDef.south.beanDefinition
        def eastWestQualifier = eastWest.declaredQualifier.toString()
        def northSouthQualifier = northSouth.declaredQualifier.toString()
        def southQualifier = south.declaredQualifier.toString()

        then:
        eastWestQualifier.contains("@Named('eastWest')")
        eastWestQualifier.contains("@Location(value=east)")
        eastWestQualifier.contains("@Location(value=west)")
        northSouthQualifier.contains("@Named('northSouth')")
        northSouthQualifier.contains("@Location(value=north)")
        northSouthQualifier.contains("@Location(value=south)")
        southQualifier.contains("@Named('south')")
        southQualifier.contains("@Location(value=south)")
        context.findBean(eastWest.asArgument(), eastWest.declaredQualifier).isPresent()
        context.findBean(northSouth.asArgument(), northSouth.declaredQualifier).isPresent()
        context.findBean(south.asArgument(), south.declaredQualifier).isPresent()

        cleanup:
        context?.close()
    }

    void "supports Scala non-binding qualifier members"() {
        when:
        def context = buildContext('''
package nonbindingqualifiers

import io.micronaut.context.annotation.NonBinding
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Singleton
class Test(
  @Cylinders(6) val v6: Engine,
  @Cylinders(8) val v8: Engine
)

@Singleton
@Cylinders(value = 6, description = "6-cylinder V6 engine")
class V6Engine extends Engine:
  override def cylinders(): Int = 6

@Singleton
@Cylinders(value = 8, description = "8-cylinder V8 engine")
class V8Engine extends Engine:
  override def cylinders(): Int = 8

trait Engine:
  def cylinders(): Int

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class Cylinders(
  val value: Int,
  @(NonBinding @getter) val description: String = ""
) extends StaticAnnotation
''')
        def bean = getBean(context, 'nonbindingqualifiers.Test')
        def v8Definition = getBeanDefinition(context, 'nonbindingqualifiers.V8Engine')

        then:
        bean.v8.cylinders() == 8
        bean.v6.cylinders() == 6
        v8Definition.declaredQualifier.qualifierAnn.memberNames == ['value'] as Set
        v8Definition.annotationMetadata
            .getAnnotation(AnnotationUtil.QUALIFIER)
            .stringValues(AnnotationUtil.NON_BINDING_ATTRIBUTE) as Set == ['description', AnnotationUtil.NON_BINDING_ATTRIBUTE] as Set

        cleanup:
        context?.close()
    }
}
