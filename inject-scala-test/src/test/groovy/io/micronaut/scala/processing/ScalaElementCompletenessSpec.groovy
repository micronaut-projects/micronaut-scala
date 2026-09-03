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

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.DefaultScope
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Requirements
import io.micronaut.context.exceptions.BeanContextException
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.core.annotation.TypeHint
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.EnumElement
import io.micronaut.inject.ast.FieldElement
import io.micronaut.inject.ast.PackageElement
import io.micronaut.inject.ast.PrimitiveElement
import io.micronaut.inject.ast.WildcardElement
import io.micronaut.inject.validation.RequiresValidation
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaEnumConstantCaptureVisitor
import io.micronaut.scala.processing.test.ScalaTypeElementQueryVisitor
import jakarta.inject.Named
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import jakarta.validation.Constraint
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

class ScalaElementCompletenessSpec extends AbstractScalaTypeElementSpec {

    void "exposes default package element metadata"() {
        when:
        def element = buildClassElement('Test', '''
class Test
''')
        PackageElement packageElement = element.getPackage()

        then:
        element.packageName == ''
        packageElement.name == ''
        packageElement.simpleName == ''
    }

    void "exposes nested package element metadata"() {
        when:
        def element = buildClassElement('abc.my.pkgeltest.PckElementTest', '''
package abc.my.pkgeltest

class PckElementTest
''')
        PackageElement packageElement = element.getPackage()

        then:
        element.packageName == 'abc.my.pkgeltest'
        packageElement.name == 'abc.my.pkgeltest'
        packageElement.simpleName == 'pkgeltest'
    }

    void "preserves primitive element equality for Scala fields"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

class Test:
  var test1: Boolean = false
''')
        def fieldType = element.getEnclosedElements(ElementQuery.ALL_FIELDS)
            .find { it.name == 'test1' }
            .type

        then:
        element != PrimitiveElement.BOOLEAN
        element != PrimitiveElement.VOID
        element != PrimitiveElement.BOOLEAN.withArrayDimensions(4)
        PrimitiveElement.VOID != element
        PrimitiveElement.INT != element
        PrimitiveElement.INT.withArrayDimensions(2) != element
        fieldType == PrimitiveElement.BOOLEAN
        PrimitiveElement.BOOLEAN == fieldType
    }

    void "exposes class body val and var properties"() {
        when:
        def element = buildClassElement('example.Worker', '''
package example

class Worker:
  val name: String = "worker"
  var started: Boolean = false
''')
        def properties = element.getBeanProperties().collectEntries { [(it.name): it] }

        then:
        properties.keySet() == ['name', 'started'] as Set
        properties.name.type.name == String.name
        properties.name.readMethod.present
        !properties.name.writeMethod.present
        properties.started.type.name == Boolean.TYPE.name
        properties.started.readMethod.present
        !properties.started.readMethod.get().returnType.isVoid()
        properties.started.writeMethod.present
        properties.started.writeMethod.get().returnType.isVoid()
    }

    void "does not expose private constructor forwarding accessors as properties"() {
        when:
        def element = buildClassElement('example.FormulaDto', '''
package example

class FormulaDto(val otherColumns: java.util.List[String], bytesValue: Array[Byte])
    extends FormulaCreationDto(bytesValue)

class FormulaCreationDto(val bytes: Array[Byte])
''')

        then:
        element.getBeanProperties()*.name == ['otherColumns']
        element.primaryConstructor.get().parameters*.name == ['otherColumns', 'bytesValue']
    }

    void "propagates field-targeted validation annotations to Scala properties"() {
        when:
        def element = buildClassElement('example.EngineConfig', '''
package example

import jakarta.validation.constraints.Min
import scala.annotation.meta.field

class EngineConfig:
  @(Min @field)(value = 1L)
  var cylinders: Int = 0
''')
        def property = element.getBeanProperties().find { it.name == 'cylinders' }

        then:
        property != null
        property.field.present
        property.field.get().hasAnnotation(Min)
        property.field.get().hasStereotype(Constraint)
        property.field.get().hasAnnotation(RequiresValidation)
        property.hasAnnotation(Min)
        property.hasStereotype(Constraint)
        property.hasAnnotation(RequiresValidation)
    }

    void "maps Scala nullable annotations on methods and parameters"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

import io.micronaut.core.annotation.Nullable

class Test:
  @Nullable
  def nullableMethod(@Nullable test: String): String = null
''')
        def method = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'nullableMethod' })).get()

        then:
        method.isNullable()
        !method.isNonNull()
        method.parameters[0].isNullable()
        !method.parameters[0].isNonNull()

        when:
        def introspection = buildBeanIntrospection('example.Test', '''
package example

import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.Nullable

@Introspected
class Test:
  @Nullable
  @Executable
  def nullableMethod(@Nullable test: String): String = null
''')
        def beanMethod = introspection.beanMethods.find { it.name == 'nullableMethod' }

        then:
        beanMethod.annotationMetadata.hasStereotype(AnnotationUtil.NULLABLE)
        !beanMethod.annotationMetadata.hasStereotype(AnnotationUtil.NON_NULL)
        beanMethod.arguments[0].isNullable()
        !beanMethod.arguments[0].isNonNull()
    }

    void "maps Scala non-null annotations on methods and parameters"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

import io.micronaut.core.annotation.NonNull

class Test:
  @NonNull
  def notNullableMethod(@NonNull test: String): String = ""
''')
        def method = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'notNullableMethod' })).get()

        then:
        !method.isNullable()
        method.isNonNull()
        !method.parameters[0].isNullable()
        method.parameters[0].isNonNull()

        when:
        def introspection = buildBeanIntrospection('example.Test', '''
package example

import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.NonNull

@Introspected
class Test:
  @NonNull
  @Executable
  def notNullableMethod(@NonNull test: String): String = ""
''')
        def beanMethod = introspection.beanMethods.find { it.name == 'notNullableMethod' }

        then:
        !beanMethod.annotationMetadata.hasStereotype(AnnotationUtil.NULLABLE)
        beanMethod.annotationMetadata.hasStereotype(AnnotationUtil.NON_NULL)
        !beanMethod.arguments[0].isNullable()
        beanMethod.arguments[0].isNonNull()
    }

    void "maps Scala nullable and non-null annotations on generic type arguments"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

import java.util.concurrent.CompletionStage
import org.jspecify.annotations.NonNull
import org.jspecify.annotations.Nullable

class Test:
  def notNullableMethod(test: String): CompletionStage[String @NonNull] = null

  def nullableMethod(test: String): CompletionStage[String @Nullable] = null

  def method(test: String): CompletionStage[String] = null
''')
        def notNullableMethod = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'notNullableMethod' })).get()
        def nullableMethod = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'nullableMethod' })).get()
        def method = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'method' })).get()

        then:
        notNullableMethod.returnType.firstTypeArgument.get().isNonNull()
        !notNullableMethod.returnType.firstTypeArgument.get().isNullable()
        !nullableMethod.returnType.firstTypeArgument.get().isNonNull()
        nullableMethod.returnType.firstTypeArgument.get().isNullable()
        !method.returnType.firstTypeArgument.get().isNonNull()
        !method.returnType.firstTypeArgument.get().isNullable()
    }

    void "maps Scala explicit null union types to nullable metadata"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

import java.util.concurrent.CompletionStage

class Test:
  def nullableMethod(test: String | Null): String | Null = test

  def method(test: String): String = test

  def genericMethod(test: String): CompletionStage[String | Null] = ???
''', ['-Yexplicit-nulls'])
        def nullableMethod = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'nullableMethod' })).get()
        def method = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'method' })).get()
        def genericMethod = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'genericMethod' })).get()

        then:
        nullableMethod.isNullable()
        nullableMethod.returnType.isNullable()
        nullableMethod.parameters[0].isNullable()
        nullableMethod.parameters[0].type.isNullable()
        !method.isNullable()
        !method.returnType.isNullable()
        !method.parameters[0].isNullable()
        !method.parameters[0].type.isNullable()
        genericMethod.returnType.firstTypeArgument.get().isNullable()
    }

    void "maps Scala explicit null constructor parameters to introspection metadata"() {
        when:
        def introspection = buildBeanIntrospection('example.Test', '''
package example

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(val name: String | Null, val title: String)
''', ['-Yexplicit-nulls'])

        then:
        introspection.constructorArguments[0].isNullable()
        !introspection.constructorArguments[1].isNullable()
    }

    void "maps Scala explicit null constructor properties to bean property metadata"() {
        when:
        def element = buildClassElement('example.Test', '''
package example

class Test(
  var name: String | Null,
  val title: String,
  val aliases: java.util.List[String | Null]
)
''', ['-Yexplicit-nulls'])
        def properties = element.beanProperties.collectEntries { [(it.name): it] }

        then:
        properties.name.isNullable()
        properties.name.type.isNullable()
        !properties.title.isNullable()
        !properties.title.type.isNullable()
        properties.aliases.type.firstTypeArgument.get().isNullable()

        when:
        def introspection = buildBeanIntrospection('example.Test', '''
package example

import io.micronaut.core.annotation.Introspected

@Introspected
class Test(
  var name: String | Null,
  val title: String
)
''', ['-Yexplicit-nulls'])
        def introspectionProperties = introspection.beanProperties.collectEntries { [(it.name): it] }

        then:
        introspectionProperties.name.isNullable()
        !introspectionProperties.title.isNullable()
    }

    void "type element query includes Scala fields"() {
        when:
        ScalaTypeElementQueryVisitor.enabled = true
        ScalaTypeElementQueryVisitor.query = ScalaTypeElementQueryVisitor.query.excludeAll().includeFields()
        def definition = buildBeanDefinition('example.QueryBean', '''
package example

import jakarta.inject.Singleton

@Singleton
class QueryBean(val name: String, var count: Int):
  private val internal: String = "hidden"
  def one(): String = name
  def two(): Int = count
''')

        then:
        definition
        ScalaTypeElementQueryVisitor.visitedClasses*.name == ['example.QueryBean']
        ScalaTypeElementQueryVisitor.visitedFields*.name as Set == ['name', 'count', 'internal'] as Set
        ScalaTypeElementQueryVisitor.visitedConstructors.empty
        ScalaTypeElementQueryVisitor.visitedMethods.empty
        ScalaTypeElementQueryVisitor.visitedEnumConstants.empty

        cleanup:
        ScalaTypeElementQueryVisitor.cleanup()
    }

    void "type element query includes Scala methods"() {
        when:
        ScalaTypeElementQueryVisitor.enabled = true
        ScalaTypeElementQueryVisitor.query = ScalaTypeElementQueryVisitor.query.excludeAll().includeMethods()
        def definition = buildBeanDefinition('example.QueryBean', '''
package example

import jakarta.inject.Singleton

@Singleton
class QueryBean(val name: String, var count: Int):
  private val internal: String = "hidden"
  def one(): String = name
  def two(): Int = count
''')

        then:
        definition
        ScalaTypeElementQueryVisitor.visitedClasses*.name == ['example.QueryBean']
        ScalaTypeElementQueryVisitor.visitedFields.empty
        ScalaTypeElementQueryVisitor.visitedConstructors.empty
        ScalaTypeElementQueryVisitor.visitedMethods*.name as Set == ['one', 'two'] as Set
        ScalaTypeElementQueryVisitor.visitedEnumConstants.empty

        cleanup:
        ScalaTypeElementQueryVisitor.cleanup()
    }

    void "type element query includes Scala constructors"() {
        when:
        ScalaTypeElementQueryVisitor.enabled = true
        ScalaTypeElementQueryVisitor.query = ScalaTypeElementQueryVisitor.query.excludeAll().includeConstructors()
        def definition = buildBeanDefinition('example.QueryBean', '''
package example

import jakarta.inject.Singleton

@Singleton
class QueryBean(val name: String, var count: Int):
  private val internal: String = "hidden"
  def one(): String = name
  def two(): Int = count
''')

        then:
        definition
        ScalaTypeElementQueryVisitor.visitedClasses*.name == ['example.QueryBean']
        ScalaTypeElementQueryVisitor.visitedFields.empty
        ScalaTypeElementQueryVisitor.visitedMethods.empty
        ScalaTypeElementQueryVisitor.visitedConstructors.size() == 1
        ScalaTypeElementQueryVisitor.visitedConstructors[0].parameters*.name == ['name', 'count']
        ScalaTypeElementQueryVisitor.visitedEnumConstants.empty

        cleanup:
        ScalaTypeElementQueryVisitor.cleanup()
    }

    void "filters inherited Scala methods with element queries"() {
        when:
        def element = buildClassElement('elementquery.Test', '''
package elementquery

abstract class Test extends SuperType, AnotherInterface, SomeInt:
  protected var t1: Boolean = false
  private var t2: Boolean = false

  private def privateMethod(): Boolean = true

  def packagePublicMethod(): Boolean = true

  override def publicMethod(): Boolean = true

  def unimplementedMethod(): Boolean

abstract class SuperType:
  protected var s1: Boolean = false
  private var s2: Boolean = false

  private def privateMethod(): Boolean = true

  def publicMethod(): Boolean = true

  def otherSuper(): Boolean = true

  def unimplementedSuperMethod(): Boolean

trait SomeInt:
  def itfeMethod(): Boolean = true

  def publicMethod(): Boolean

trait AnotherInterface:
  def publicMethod(): Boolean

  def unimplementedItfeMethod(): Boolean
''')
        def allMethods = element.getEnclosedElements(ElementQuery.ALL_METHODS)
        def abstractMethods = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyAbstract())
        def concreteMethods = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyConcrete().onlyAccessible())

        then:
        allMethods*.name.containsAll([
            'privateMethod',
            'packagePublicMethod',
            'publicMethod',
            'unimplementedMethod',
            'otherSuper',
            'unimplementedSuperMethod',
            'itfeMethod',
            'unimplementedItfeMethod'
        ])
        abstractMethods*.name as Set == ['unimplementedItfeMethod', 'unimplementedSuperMethod', 'unimplementedMethod'] as Set
        concreteMethods*.name as Set == ['packagePublicMethod', 'publicMethod', 'otherSuper', 'itfeMethod'] as Set
    }

    void "filters emitted Scala fields with element queries"() {
        when:
        def element = buildClassElement('elementquery.FieldQuery', '''
package elementquery

class FieldQuery:
  val publicVal: String = "public"
  protected val protectedVal: String = "protected"
  private val privateVal: String = "private"
  var mutableVar: Int = 1
''')
        def allFields = element.getEnclosedElements(ElementQuery.ALL_FIELDS)
        def privateFields = element.getEnclosedElements(ElementQuery.ALL_FIELDS.modifiers({
            it.contains(ElementModifier.PRIVATE)
        }))
        def accessibleFields = element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyAccessible())

        then:
        allFields*.name as Set == ['publicVal', 'protectedVal', 'privateVal', 'mutableVar'] as Set
        allFields.every { it.private }
        allFields.every { it.reflectionRequired }
        privateFields*.name as Set == allFields*.name as Set
        accessibleFields.empty
    }

    void "exposes generic property type arguments"() {
        when:
        def element = buildClassElement('example.Holder', '''
package example

class Holder(
  val names: java.util.List[String],
  val lookup: java.util.Map[String, java.lang.Integer]
)
''')
        def properties = element.getBeanProperties().collectEntries { [(it.name): it] }

        then:
        properties.names.type.name == 'java.util.List'
        properties.names.type.typeArguments.keySet() == ['E'] as Set
        properties.names.type.typeArguments.E.name == String.name
        properties.names.type.isAssignable(Collection)
        properties.lookup.type.name == 'java.util.Map'
        properties.lookup.type.typeArguments.keySet() == ['K', 'V'] as Set
        properties.lookup.type.typeArguments.K.name == String.name
        properties.lookup.type.typeArguments.V.name == Integer.name
    }

    void "exposes inherited generic property type arguments"() {
        when:
        def element = buildClassElement('example.Holder', '''
package example

class Holder(val names: java.util.List[String])
''')
        def namesType = element.getBeanProperties().find { it.name == 'names' }.type

        then:
        namesType.getAllTypeArguments().get(List.name).get("E").name == String.name
        namesType.getAllTypeArguments().get(Collection.name).get("E").name == String.name
        namesType.getAllTypeArguments().get(Iterable.name).get("T").name == String.name
    }

    void "exposes Scala generic placeholders with upper bounds"() {
        when:
        def element = buildClassElement('example.Holder', '''
package example

class Holder[T <: Number](val value: T)
''')
        def placeholder = element.declaredGenericPlaceholders.first()
        def propertyType = element.getBeanProperties().find { it.name == 'value' }.type

        then:
        placeholder.variableName == 'T'
        placeholder.name == Number.name
        placeholder.bounds*.name == [Number.name]
        propertyType.genericPlaceholder
        propertyType.typeVariable
        propertyType.variableName == 'T'
        propertyType.name == Number.name
        propertyType.bounds*.name == [Number.name]
    }

    void "exposes Scala method generic placeholders with upper bounds"() {
        when:
        def element = buildClassElement('example.GenericMethods', '''
package example

class GenericMethods:
  def identity[T <: Number](value: T): T = value
''')
        def method = element.findMethod('identity').get()
        def placeholder = method.declaredTypeVariables.first()

        then:
        placeholder.variableName == 'T'
        placeholder.name == Number.name
        placeholder.bounds*.name == [Number.name]
        method.declaredTypeArguments.T.variableName == 'T'
        method.returnType.genericPlaceholder
        method.returnType.variableName == 'T'
        method.parameters[0].type.genericPlaceholder
        method.parameters[0].type.variableName == 'T'
    }

    void "exposes Scala throws annotations as method thrown types"() {
        when:
        def element = buildClassElement('throwstest.Test', '''
package throwstest

import io.micronaut.context.exceptions.BeanContextException

class Test:
  @throws[java.sql.SQLException]
  @throws[BeanContextException]
  def test(): Unit = ()
''')
        def method = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'test' })).get()

        then:
        method.thrownTypes*.name as Set == [java.sql.SQLException.name, BeanContextException.name] as Set
    }

    void "exposes Scala wildcard generic type arguments"() {
        when:
        def element = buildClassElement('example.Holder', '''
package example

class Holder(
  val anything: java.util.List[_],
  val numbers: java.util.List[_ <: Number],
  val integersOrSuper: java.util.List[_ >: Integer]
)
''')
        def properties = element.getBeanProperties().collectEntries { [(it.name): it] }
        def anything = properties.anything.type.typeArguments.E as WildcardElement
        def numbers = properties.numbers.type.typeArguments.E as WildcardElement
        def integersOrSuper = properties.integersOrSuper.type.typeArguments.E as WildcardElement

        then:
        anything.wildcard
        !anything.bounded
        anything.name == Object.name
        anything.upperBounds*.name == [Object.name]
        anything.lowerBounds.empty
        numbers.wildcard
        numbers.bounded
        numbers.name == Number.name
        numbers.upperBounds*.name == [Number.name]
        numbers.lowerBounds.empty
        integersOrSuper.wildcard
        integersOrSuper.bounded
        integersOrSuper.name == Object.name
        integersOrSuper.upperBounds*.name == [Object.name]
        integersOrSuper.lowerBounds*.name == [Integer.name]
    }

    void "exposes Scala wildcard bounds that reference generic placeholders"() {
        when:
        def element = buildClassElement('example.Holder', '''
package example

class Holder[T <: CharSequence](val values: java.util.List[_ <: T])
''')
        def values = element.getBeanProperties().find { it.name == 'values' }.type
        def wildcard = values.typeArguments.E as WildcardElement
        def upperBound = wildcard.upperBounds.first()

        then:
        wildcard.wildcard
        wildcard.bounded
        wildcard.name == CharSequence.name
        upperBound.genericPlaceholder
        upperBound.variableName == 'T'
        upperBound.name == CharSequence.name
        upperBound.bounds*.name == [CharSequence.name]
    }

    void "terminates recursive Scala generic type parameter bounds"() {
        when:
        def element = buildClassElement('test.Test', '''
package test

final class Test[T <: Test[T]]
''')
        def typeArguments = element.typeArguments
        def typeArgument = typeArguments.T
        def nextTypeArguments = typeArgument.typeArguments
        def nextTypeArgument = nextTypeArguments.T
        def nextNextTypeArguments = nextTypeArgument.typeArguments
        def nextNextTypeArgument = nextNextTypeArguments.T

        then:
        typeArguments.size() == 1
        typeArgument.name == 'test.Test'
        nextTypeArgument.name == 'test.Test'
        nextNextTypeArgument.name == Object.name
    }

    void "exposes Scala field constant values"() {
        when:
        def element = buildClassElement('example.Constants', '''
package example

class Constants:
  final val port: Int = 8080
  final val name: String = "primary"
  var mutable: Int = 1
''')
        def fields = element.getEnclosedElements(ElementQuery.of(FieldElement)).collectEntries { [(it.name): it] }

        then:
        fields.port.constantValue == 8080
        fields.name.constantValue == 'primary'
        fields.mutable.constantValue == null
    }

    void "exposes traits as interfaces and resolves inherited assignability"() {
        when:
        def element = buildClassElement('example.Vehicle', '''
package example

trait Machine
trait Engine extends Machine
class Vehicle extends Engine
''')

        then:
        !element.superType.present
        element.interfaces*.name == ['example.Engine']
        element.isAssignable('example.Engine')
        element.isAssignable('example.Machine')
    }

    void "exposes inherited Java interfaces from compiler symbols"() {
        when:
        def element = buildClassElement('example.TimingInterceptor', '''
package example

import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext

class TimingInterceptor extends MethodInterceptor[AnyRef, Object]:
  override def intercept(context: MethodInvocationContext[AnyRef, Object]): Object =
    context.proceed()
''')
        def methodInterceptor = element.interfaces.first()

        then:
        !element.interface
        element.interfaces*.name == ['io.micronaut.aop.MethodInterceptor']
        methodInterceptor.interfaces*.name == ['io.micronaut.aop.Interceptor']
        element.isAssignable('io.micronaut.aop.MethodInterceptor')
        element.isAssignable('io.micronaut.aop.Interceptor')
    }

    void "propagates annotations from inherited Scala interface type arguments"() {
        when:
        def element = buildClassElement('example.MyRepo', '''
package example

import jakarta.validation.Valid

trait MyRepo extends Repo[MyBean @Valid, java.lang.Long]

trait Repo[E, ID] extends GenericRepository[E, ID]:
  def save(entity: E): Unit

trait GenericRepository[E, ID]

class MyBean:
  var name: String = ""
''')
        def method = element.findMethod('save').get()
        def parameterType = method.parameters[0].genericType

        then:
        parameterType.name == 'example.MyBean'
        parameterType.hasAnnotation(Valid)
    }

    void "exposes Scala inner classes through enclosed elements"() {
        when:
        def element = buildClassElement('example.Outer', '''
package example

class Outer:
  class Inner
''')
        def inner = element.getEnclosedElements(ElementQuery.of(ClassElement)).first()

        then:
        inner.name == 'example.Outer$Inner'
        inner.inner
        inner.enclosingType.get().name == 'example.Outer'
    }

    void "exposes companion object nested classes through companion enclosed elements"() {
        when:
        def element = buildClassElement('example.Outer', '''
package example

class Outer
object Outer:
  class Nested
''')
        def nested = element.getEnclosedElements(ElementQuery.of(ClassElement)).first()

        then:
        nested.name == 'example.Outer$Nested'
        nested.inner
        nested.enclosingType.get().name == 'example.Outer'
    }

    void "exposes Scala enum constants through enum elements"() {
        when:
        def element = buildClassElement('example.Color', '''
package example

enum Color:
  case Red, Blue
''')
        def enumElement = (EnumElement) element

        then:
        element.enum
        enumElement.values() == ['Red', 'Blue']
        enumElement.elements()*.name == ['Red', 'Blue']
        enumElement.elements()*.type*.name == ['example.Color', 'example.Color']
    }

    void "type element visitors see Scala enum constants"() {
        given:
        def constants = []

        when:
        ScalaEnumConstantCaptureVisitor.withConsumer(constants::add, {
            buildClassLoader('example.Color', '''
package example

enum Color:
  case Red, Blue
''')
        })

        then:
        constants*.name == ['Red', 'Blue']
    }

    void "exposes array class literal and enum annotation values"() {
        when:
        def element = buildClassElement('example.Hints', '''
package example

import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.TypeHint

@Requires(env = Array("test", "dev"))
@TypeHint(value = Array(classOf[String]), accessType = Array(TypeHint.AccessType.ALL_PUBLIC))
class Hints
''')
        def requires = element.getAnnotation(Requires)
        def typeHint = element.getAnnotation(TypeHint)

        then:
        requires.stringValues('env').toList() == ['test', 'dev']
        typeHint.annotationClassValues('value').collect { it.name } == [String.name]
        typeHint.enumValues('accessType', TypeHint.AccessType).toList() == [TypeHint.AccessType.ALL_PUBLIC]
    }

    void "resolves Scala object constants used as annotation values"() {
        when:
        def element = buildClassElement('example.ConditionalBean', '''
package example

import io.micronaut.context.annotation.Requires

object FeatureConfig:
  inline val Property = "feature.enabled"
  final val ExpectedValue = "true"

@Requires(property = FeatureConfig.Property, value = FeatureConfig.ExpectedValue)
class ConditionalBean
''')
        def requires = element.getAnnotation(Requires)

        then:
        requires.stringValue('property').get() == 'feature.enabled'
        requires.stringValue('value').get() == 'true'
    }

    void "expands nested repeatable annotation container values"() {
        when:
        def element = buildClassElement('example.ConditionalBean', '''
package example

import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Requirements

@Requirements(Array(
  new Requires(env = Array("test")),
  new Requires(property = "feature.enabled", value = "true")
))
class ConditionalBean
''')

        def requirements = element.getAnnotationValuesByType(Requires)

        then:
        element.hasAnnotation(Requirements)
        requirements.size() == 2
        requirements[0].stringValues('env').toList() == ['test']
        requirements[1].stringValue('property').get() == 'feature.enabled'
        requirements[1].stringValue('value').get() == 'true'
    }

    void "expands direct repeatable annotations"() {
        when:
        def element = buildClassElement('example.DirectConditionalBean', '''
package example

import io.micronaut.context.annotation.Requires

@Requires(env = Array("test"))
@Requires(property = "feature.enabled", value = "true")
class DirectConditionalBean
''')

        def requirements = element.getAnnotationValuesByType(Requires)
        def envRequirement = requirements.find { it.stringValues('env').toList() == ['test'] }
        def propertyRequirement = requirements.find { it.stringValue('property').orElse(null) == 'feature.enabled' }

        then:
        requirements.size() == 2
        envRequirement != null
        propertyRequirement != null
        propertyRequirement.stringValue('value').get() == 'true'
    }

    void "traverses source-defined Scala annotation stereotypes"() {
        when:
        def element = buildClassElement('example.Engine', '''
package example

import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Singleton
class MySingleton extends StaticAnnotation

@MySingleton
class Engine
''')

        then:
        element.hasAnnotation('example.MySingleton')
        element.hasStereotype(Singleton)
        element.hasStereotype('jakarta.inject.Scope')
    }

    void "reads source-defined Scala annotation defaults from compiler symbols"() {
        when:
        def element = buildClassElement('example.Engine', '''
package example

import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Singleton
class MySingleton(val value: String = "engine", val enabled: Boolean = true) extends StaticAnnotation

@MySingleton
class Engine
''')
        def annotation = element.getAnnotation('example.MySingleton')

        then:
        annotation.stringValue().get() == 'engine'
        annotation.booleanValue('enabled').get()
        element.hasStereotype(Singleton)
    }

    void "exposes source-defined Scala annotation classes as class elements"() {
        when:
        def marker = buildClassElement('example.MySingleton', '''
package example

import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Singleton
class MySingleton extends StaticAnnotation

@MySingleton
class Engine
''')

        then:
        marker != null
        marker.name == 'example.MySingleton'
        marker.hasStereotype(Singleton)
    }

    void "exposes source-defined Scala annotation retention and targets"() {
        when:
        def marker = buildClassElement('example.RuntimeMarker', '''
package example

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
class RuntimeMarker extends StaticAnnotation

@RuntimeMarker
class Engine:
  @RuntimeMarker
  def start(): Unit = ()
''')
        def target = marker.getAnnotation(Target)
        def retention = marker.getAnnotation(Retention)

        then:
        marker != null
        retention.enumValue('value', RetentionPolicy).get() == RetentionPolicy.RUNTIME
        target.enumValues('value', ElementType).toList() == [ElementType.TYPE, ElementType.METHOD]
    }

    void "resolves alias target annotation stereotypes from class literal symbols"() {
        when:
        def element = buildClassElement('example.Engine', '''
package example

import io.micronaut.context.annotation.AliasFor
import jakarta.inject.Named
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

class NamedAlias(
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val value: String = ""
) extends StaticAnnotation

@NamedAlias("main")
class Engine
''')

        then:
        element.hasStereotype(Named)
        element.hasStereotype(Qualifier)
        element.getAnnotationValuesByStereotype(Named.name).find {
            it.annotationName == 'example.NamedAlias'
        }.stringValue().get() == 'main'
    }

    void "inherits source-defined annotations through class hierarchy"() {
        when:
        def element = buildClassElement('example.Engine', '''
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

@InheritedSingleton
class Machine

class Engine extends Machine
''')

        then:
        element.hasAnnotation('example.InheritedSingleton')
        !element.hasDeclaredAnnotation('example.InheritedSingleton')
        element.hasStereotype(Singleton)
        !element.hasDeclaredStereotype(Singleton)
    }

    void "inherits classpath annotations through compiler symbols"() {
        when:
        def inherited = buildClassElement('example.Engine', '''
package example

import io.micronaut.scala.processing.fixtures.ExternalMachine

class Engine extends ExternalMachine
''')
        def local = buildClassElement('example.LocalEngine', '''
package example

import io.micronaut.scala.processing.fixtures.ExternalLocalMachine

class LocalEngine extends ExternalLocalMachine
''')

        then:
        inherited.hasAnnotation('io.micronaut.scala.processing.fixtures.ExternalInheritedSingleton')
        !inherited.hasDeclaredAnnotation('io.micronaut.scala.processing.fixtures.ExternalInheritedSingleton')
        inherited.hasStereotype(Singleton)
        !inherited.hasDeclaredStereotype(Singleton)
        !local.hasAnnotation('io.micronaut.scala.processing.fixtures.ExternalLocalSingleton')
        !local.hasStereotype(Singleton)
    }

    void "preserves Scala element equality across metadata wrapper copies"() {
        when:
        def element = buildClassElement('example.Vehicle', '''
package example

class Vehicle(var name: String):
  val engine: String = "v8"
  def start(speed: Int): String = name + speed.toString
  def start(speed: String): String = name + speed
  def combine(left: String, right: String): String = left + right
''')
        def classCopy = element.withAnnotationMetadata(element.annotationMetadata)
        def methods = element.getEnclosedElements(ElementQuery.ALL_METHODS)
        def startMethods = methods.findAll { it.name == 'start' }
        def startMethod = startMethods[0]
        def startMethodCopy = startMethod.withAnnotationMetadata(startMethod.annotationMetadata)
        def overloadedStartMethod = startMethods[1]
        def combineMethod = methods.find { it.name == 'combine' }
        def engineField = element.getEnclosedElements(ElementQuery.ALL_FIELDS).find { it.name == 'engine' }
        def engineFieldCopy = engineField.withAnnotationMetadata(engineField.annotationMetadata)
        def nameProperty = element.beanProperties.find { it.name == 'name' }
        def namePropertyCopy = nameProperty.withAnnotationMetadata(nameProperty.annotationMetadata)
        def constructor = element.primaryConstructor.get()
        def constructorCopy = constructor.withAnnotationMetadata(constructor.annotationMetadata)
        def nameParameter = constructor.parameters[0]
        def nameParameterCopy = constructorCopy.parameters[0].withAnnotationMetadata(constructorCopy.parameters[0].annotationMetadata)

        then:
        element == classCopy
        element.hashCode() == classCopy.hashCode()
        startMethod == startMethodCopy
        startMethod.hashCode() == startMethodCopy.hashCode()
        startMethods.size() == 2
        startMethod != overloadedStartMethod
        combineMethod.parameters[0] != combineMethod.parameters[1]
        engineField == engineFieldCopy
        engineField.hashCode() == engineFieldCopy.hashCode()
        nameProperty == namePropertyCopy
        nameProperty.hashCode() == namePropertyCopy.hashCode()
        constructor == constructorCopy
        constructor.hashCode() == constructorCopy.hashCode()
        nameParameter == nameParameterCopy
        nameParameter.hashCode() == nameParameterCopy.hashCode()
        nameProperty != nameParameter
    }

    void "keeps Scala method mutation hierarchy out of declared metadata"() {
        when:
        def element = buildClassElement('example.EngineFactory', '''
package example

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory

@Factory
class EngineFactory:
  @Bean
  def engine(): Engine = Engine()

class Engine
''')
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS).find { it.name == 'engine' }
        def mutated = method.withAnnotationMetadata(new AnnotationMetadataHierarchy(element.annotationMetadata, method.methodAnnotationMetadata))

        then:
        mutated == method
        mutated.hashCode() == method.hashCode()
        mutated.annotationMetadata.hasStereotype(DefaultScope)
        mutated.declaredMetadata.hasDeclaredAnnotation(Bean)
        !mutated.declaredMetadata.hasStereotype(DefaultScope)
        !mutated.declaredMetadata.hasDeclaredAnnotation(Factory)
    }
}
