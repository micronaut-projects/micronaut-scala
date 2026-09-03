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

import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.core.beans.BeanIntrospectionReference
import io.micronaut.core.beans.EnumBeanIntrospection
import io.micronaut.core.type.GenericPlaceholder
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

class ScalaBeanIntrospectionSpec extends AbstractScalaTypeElementSpec {

    void "loads Scala introspection reference with validation metadata"() {
        given:
        def classLoader = buildClassLoader('test.Address', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import scala.annotation.meta.getter

@Introspected
class Address(
  @(NotBlank @getter)(groups = Array(classOf[GroupOne]))
  @(Size @getter)(min = 5, max = 20, groups = Array(classOf[GroupTwo]))
  val street: String
)

trait GroupOne
trait GroupTwo
''')

        when:
        BeanIntrospectionReference reference = classLoader.loadClass('test.$Address$Introspection').getDeclaredConstructor().newInstance()
        BeanIntrospection introspection = reference.load()
        def property = introspection.getRequiredProperty("street", String)

        then:
        reference != null
        introspection != null
        property.hasAnnotation(NotBlank)
        property.hasAnnotation(Size)
        property.getAnnotationMetadata().getAnnotationValuesByType(NotBlank).first().classValues("groups")*.name == ['test.GroupOne']
        property.getAnnotationMetadata().getAnnotationValuesByType(Size).first().classValues("groups")*.name == ['test.GroupTwo']
    }

    void "exposes Scala constructor argument generics"() {
        when:
        def introspection = buildBeanIntrospection('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(val properties: java.util.Map[String, String])
''')

        then:
        introspection.constructorArguments[0].getTypeVariable("K").get().type == String
        introspection.constructorArguments[0].getTypeVariable("V").get().type == String
    }

    void "exposes Scala generic array introspection types"() {
        when:
        def introspection = buildBeanIntrospection('arraygenerics.Test', '''
package arraygenerics

import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected

@Introspected
class Test[T <: CharSequence](
  var array: Array[T],
  var starArray: Array[_],
  var stringArray: Array[String]
):
  @Executable
  def myMethod(): Array[T] = array
''')

        then:
        introspection.beanProperties.size() == 3
        introspection.getRequiredProperty("array", CharSequence[].class).type == CharSequence[].class
        introspection.getRequiredProperty("starArray", Object[].class).type == Object[].class
        introspection.getRequiredProperty("stringArray", String[].class).type == String[].class
        introspection.beanMethods.first().returnType.type == CharSequence[].class
    }

    void "exposes Scala multi-dimensional array introspection types"() {
        when:
        def introspection = buildBeanIntrospection('arraygenerics.Test', '''
package arraygenerics

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(
  var oneDimension: Array[Int],
  var twoDimensions: Array[Array[Int]],
  var stringMatrix: Array[Array[String]]
)
''')

        then:
        introspection.getRequiredProperty("oneDimension", int[].class).type == int[].class
        introspection.getRequiredProperty("twoDimensions", int[][].class).type == int[][].class
        introspection.getRequiredProperty("stringMatrix", String[][].class).type == String[][].class
    }

    void "exposes annotation metadata on deep Scala introspection property type parameters"() {
        when:
        def introspection = buildBeanIntrospection('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Introspected
class Test(
  var deepList: java.util.List[
    java.util.List[
      java.util.List[String @NotNull] @NotEmpty
    ] @Size(min = 1, max = 2)
  ],
  var deepList2: java.util.List[
    java.util.List[
      java.util.List[
        java.util.List[
          java.util.List[
            java.util.List[String]
          ]
        ]
      ]
    ]
  ]
)
''')

        then:
        introspection != null
        def property = introspection.getProperty("deepList").get().asArgument()
        property.getTypeParameters().length == 1
        def param1 = property.getTypeParameters()[0]
        param1.getTypeParameters().length == 1
        def param2 = param1.getTypeParameters()[0]
        param2.getTypeParameters().length == 1
        def param3 = param2.getTypeParameters()[0]
        param1.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.Size$List')
        param2.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.NotEmpty$List')
        param3.getAnnotationMetadata().getAnnotationNames().contains('jakarta.validation.constraints.NotNull$List')
    }

    void "supports Scala field access bean introspection"() {
        when:
        def introspection = buildBeanIntrospection('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(
  accessKind = Array(Introspected.AccessKind.FIELD),
  visibility = Array(Introspected.Visibility.ANY)
)
class Test:
  private var secret: String = "hidden"
  var visible: String = "shown"
  def reveal: String = secret
''')
        def bean = introspection.instantiate()
        def properties = introspection.beanProperties.collectEntries { [(it.name): it] }

        then:
        properties.keySet() == ['secret', 'visible'] as Set
        properties.secret.get(bean) == 'hidden'
        properties.visible.get(bean) == 'shown'
    }

    void "honors Scala introspection include and exclude rules"() {
        when:
        def classLoader = buildClassLoader('test.IncludedUser', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(includes = Array("name", "rating"))
class IncludedUser(var name: String, var rating: Int, var secret: String)

@Introspected(excludes = Array("secret"))
class ExcludedUser(var name: String, var rating: Int, var secret: String)
''')
        BeanIntrospection included = classLoader.loadClass('test.$IncludedUser$Introspection').getDeclaredConstructor().newInstance()
        BeanIntrospection excluded = classLoader.loadClass('test.$ExcludedUser$Introspection').getDeclaredConstructor().newInstance()

        then:
        included.propertyNames as Set == ['name', 'rating'] as Set
        included.getRequiredProperty("name", String)
        included.getRequiredProperty("rating", Integer.TYPE)
        included.getProperty("secret").isEmpty()
        excluded.propertyNames as Set == ['name', 'rating'] as Set
        excluded.getRequiredProperty("name", String)
        excluded.getRequiredProperty("rating", Integer.TYPE)
        excluded.getProperty("secret").isEmpty()
    }

    void "exposes Scala numbered introspection properties"() {
        when:
        def introspection = buildBeanIntrospection('test.Document', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Document(var line1: String, var value2: Int)
''')
        def properties = introspection.beanProperties.collectEntries { [(it.name): it] }

        then:
        properties.line1.type == String
        properties.value2.type == Integer.TYPE
    }

    void "exposes Scala covariant JavaBean-style introspection properties"() {
        when:
        def introspection = buildBeanIntrospection('test.Document', '''
package test

import io.micronaut.core.annotation.Introspected

trait Readable:
  def getContent(): CharSequence

@Introspected
class Document(var line1: String, var value2: Int) extends Readable:
  private var contentValue: String = _
  override def getContent(): String = contentValue
  def setContent(content: String): Unit =
    contentValue = content
''')
        def properties = introspection.beanProperties.collectEntries { [(it.name): it] }

        then:
        properties.content.type == String
        properties.content.isReadWrite()
    }

    void "exposes overloaded Scala executable introspection methods"() {
        when:
        def introspection = buildBeanIntrospection('test.Calculator', '''
package test

import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected

@Introspected
class Calculator:
  @Executable
  def convert(value: String): String = value

  @Executable
  def convert(value: Int): Int = value
''')
        def methods = introspection.beanMethods.findAll { it.name == 'convert' }

        then:
        methods.size() == 2
        methods.collect { it.arguments*.type } as Set == [[String], [Integer.TYPE]] as Set
        methods*.returnType*.type as Set == [String, Integer.TYPE] as Set
    }

    void "builds Scala introspection for companion nested class"() {
        when:
        def introspection = buildBeanIntrospection('test.Test$Foo', '''
package test

import io.micronaut.core.annotation.Introspected

object Test:
  @Introspected
  class Foo(val name: String)
''')

        then:
        introspection != null
        introspection.beanType.simpleName == 'Foo'
        introspection.getRequiredProperty("name", String).get(introspection.instantiate("Fred")) == "Fred"
    }

    void "writes Scala introspection to custom target package"() {
        when:
        def classLoader = buildClassLoader('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(targetPackage = "test.introspections")
class Test(val name: String)
''')
        def introspectionName = 'test.introspections.$Test$Introspection'
        def introspection = classLoader.loadClass(introspectionName).getDeclaredConstructor().newInstance() as BeanIntrospection
        def introspectionRef = classLoader.loadClass(introspectionName).getDeclaredConstructor().newInstance() as BeanIntrospectionReference

        then:
        introspection.beanType.name == 'test.Test'
        introspectionRef.beanType.name == 'test.Test'
        introspection.getRequiredProperty("name", String).get(introspection.instantiate("Fred")) == "Fred"
    }

    void "writes Scala external class introspection from an introspection target"() {
        when:
        def classLoader = buildClassLoader('test.IntrospectionConfig', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(classes = Array(classOf[ExternalBook]))
class IntrospectionConfig

class ExternalBook(var title: String, var pages: Int)
''')
        BeanIntrospection introspection = classLoader.loadClass('test.$test_ExternalBook$Introspection').getDeclaredConstructor().newInstance()
        def book = introspection.instantiate("Micronaut", 42)

        then:
        introspection.beanType.name == 'test.ExternalBook'
        introspection.propertyNames as Set == ['title', 'pages'] as Set
        introspection.getRequiredProperty("title", String).get(book) == "Micronaut"
        introspection.getRequiredProperty("pages", Integer.TYPE).get(book) == 42
    }

    void "instantiates Scala enum bean introspection by value name"() {
        when:
        def introspection = buildBeanIntrospection('test.Status', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
enum Status(val code: String):
  case Active extends Status("A")
  case Disabled extends Status("D")
''')
        def active = introspection.instantiate("Active")

        then:
        introspection instanceof EnumBeanIntrospection
        introspection.beanProperties*.name == ['code']
        active.toString() == "Active"
        introspection.getRequiredProperty("code", String).get(active) == "A"
    }

    void "exposes Scala enum constants through bean introspection"() {
        when:
        def introspection = buildBeanIntrospection('test.Status', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
enum Status:
  case Active, Disabled
''')

        then:
        introspection instanceof EnumBeanIntrospection
        introspection.constants*.value*.toString() == ['Active', 'Disabled']
    }

    void "generates reflection-free Scala enum value access"() {
        when:
        def classLoader = buildClassLoader('test.Status', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
enum Status:
  case Active, Disabled
''')
        def introspectionClass = classLoader.loadClass('test.$Status$Introspection')
        def introspection = introspectionClass.getDeclaredConstructor().newInstance()
        def introspectionBytes = classLoader.getResourceAsStream('test/$Status$Introspection.class').withCloseable {
            it.bytes
        }
        def constantPoolText = new String(introspectionBytes, 'ISO-8859-1')

        then:
        introspection instanceof EnumBeanIntrospection
        introspection.constants*.value*.toString() == ['Active', 'Disabled']
        introspection.constants*.class*.name as Set == [
                'io.micronaut.inject.beans.AbstractEnumBeanIntrospectionAndReference$EnumConstantObjectRef'
        ] as Set
        constantPoolText.contains('valueOf')
        !constantPoolText.contains('java/lang/reflect/Method')
        !constantPoolText.contains('getMethod')
    }

    void "instantiates Scala introspection with byte array constructor forwarding"() {
        when:
        def introspection = buildBeanIntrospection('test.FormulaDto', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class FormulaDto(val otherColumns: java.util.List[String], bytesValue: Array[Byte])
    extends FormulaCreationDto(bytesValue)

@Introspected
class FormulaCreationDto(val bytes: Array[Byte])
''')
        def bytes = new byte[] { 123 }
        def bean = introspection.instantiate(List.of("total"), bytes)

        then:
        bean.otherColumns() == List.of("total")
        bean.bytes().is(bytes)
    }

    void "instantiates Scala introspection with boxed Boolean constructor forwarding"() {
        when:
        def introspection = buildBeanIntrospection('test.FormulaDto', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class FormulaDto(val otherColumns: java.util.List[String], percentValue: java.lang.Boolean)
    extends FormulaCreationDto(java.util.Optional.of(percentValue))

@Introspected
class FormulaCreationDto(percentValue: java.util.Optional[java.lang.Boolean]):
  val percent: Boolean = percentValue.orElse(false)
''')
        def bean = introspection.instantiate(List.of("percent"), Boolean.TRUE)

        then:
        bean.otherColumns() == List.of("percent")
        bean.percent()
    }

    void "exposes Scala introspection subtype generic placeholders"() {
        given:
        def introspection = buildBeanIntrospection('test.Holder', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Animal

@Introspected
class Cat(val lives: Int) extends Animal

@Introspected
class Holder[A <: Animal](
  val animalNonGeneric: Animal,
  val animalsNonGeneric: List[Animal],
  val animal: A,
  val animals: List[A]
)
''')

        expect:
        def animalListArgument = introspection.getProperty("animals").get().asArgument().getTypeParameters()[0]
        animalListArgument instanceof GenericPlaceholder
        animalListArgument.isTypeVariable()

        def animal = introspection.getProperty("animal").get().asArgument()
        animal instanceof GenericPlaceholder
        animal.isTypeVariable()
    }

    void "handles Scala introspection with protobuf-style generic superclass"() {
        when:
        buildBeanIntrospection('test.MyMessage', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class MyMessage extends Message

class Message:
  def getBuilder(): Builder[?] = Builder()

class Builder[BuilderT <: Builder[BuilderT]]:
  class BuilderParentImpl
  private var meAsParent: BuilderParentImpl = _
''')

        then:
        noExceptionThrown()
    }
}
