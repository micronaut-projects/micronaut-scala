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

import io.micronaut.core.type.Argument
import io.micronaut.core.type.WildcardArgument
import io.micronaut.inject.BeanDefinition
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import spock.lang.Shared
import spock.lang.Unroll

/**
 * The Scala counterpart of Core's {@code WildcardTypeArgumentSpec} (Java, Kotlin and Groovy):
 * a wildcard type argument is compiled to a {@code WildcardArgument} carrying its bounds, and
 * one bounded by a type compares equal in type to the argument written with that type.
 */
class ScalaWildcardTypeArgumentSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import io.micronaut.context.annotation.Executable
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.List
import java.util.Map
import java.util.Optional
import java.util.function.Consumer

trait Foo[T]
trait Bounded[T <: Number]
class Book
abstract class Base[A, B]

@Singleton
class Bean[B <: Book](
    unbounded: Foo[?],
    obj: Foo[Object],
    variable: Foo[B],
    upper: List[? <: Number],
    lower: Consumer[? >: Book],
    implicitBound: Bounded[?],
    nested: List[List[? <: Number]],
    classVariableBound: List[? <: B],
    parameterizedBound: List[? <: Comparable[String]],
    two: Map[? <: CharSequence, ? >: Book],
    array: Array[List[? <: Number]],
    optionalNested: Optional[? <: Foo[?]]
) extends Base[Foo[?], List[? >: Book]] with Foo[List[? <: Number]] {

  @Inject var field: Foo[?] = null
  @Inject var mapField: Map[? <: CharSequence, ? >: Book] = null

  @Inject
  def inject(injected: Foo[?], lowerInjected: List[? >: Book]): Unit = ()

  @Executable
  def on(unbounded: Foo[?], upper: List[? <: Number], lower: Consumer[? >: Book]): Unit = ()

  @Executable
  def methodVariable[M <: Number](methodVariableBound: List[? <: M], variable: Foo[M]): Unit = ()

  @Executable
  def returnsUpper(): List[? <: Number] = null

  @Executable
  def returnsTwo(): Map[? <: CharSequence, ? >: Book] = null

  @Executable
  def returnsObject(): Foo[Object] = null
}
'''

    @Shared BeanDefinition<?> definition

    def setupSpec() {
        definition = buildBeanDefinition('test.Bean', SOURCE)
    }

    private static WildcardArgument<?> wildcard(Argument<?> argument) {
        argument instanceof WildcardArgument ? (WildcardArgument<?>) argument : null
    }

    private static List<String> upper(Argument<?> argument) {
        wildcard(argument)?.upperBounds*.type*.name
    }

    private static List<String> lower(Argument<?> argument) {
        wildcard(argument)?.lowerBounds*.type*.name
    }

    private Map<String, Argument<?>> constructorArguments() {
        definition.constructor.arguments.collectEntries { [it.name, it] }
    }

    @Unroll
    void 'constructor parameter #name compiles the wildcard to #type bounded by #upperBounds above and #lowerBounds below'() {
        given:
        Argument<?> typeArgument = constructorArguments()[name].typeParameters[0]

        expect:
        typeArgument.type.name == type
        upper(typeArgument) == upperBounds
        lower(typeArgument) == lowerBounds

        where:
        name                 | type                   | upperBounds              | lowerBounds
        'unbounded'          | 'java.lang.Object'     | ['java.lang.Object']     | []
        'upper'              | 'java.lang.Number'     | ['java.lang.Number']     | []
        'lower'              | 'test.Book'            | ['java.lang.Object']     | ['test.Book']
        'implicitBound'      | 'java.lang.Number'     | ['java.lang.Object']     | []
        'classVariableBound' | 'test.Book'            | ['test.Book']            | []
        'parameterizedBound' | 'java.lang.Comparable' | ['java.lang.Comparable'] | []
        'obj'                | 'java.lang.Object'     | null                     | null
        'variable'           | 'test.Book'            | null                     | null
    }

    void 'a type argument that is not a wildcard is not marked'() {
        given:
        Map<String, Argument<?>> arguments = constructorArguments()

        expect:
        !(arguments.obj.typeParameters[0] instanceof WildcardArgument)
        !arguments.obj.typeParameters[0].isTypeVariable()

        and: 'a class type variable stays a type variable'
        arguments.variable.typeParameters[0].isTypeVariable()
        !(arguments.variable.typeParameters[0] instanceof WildcardArgument)

        and: 'the enclosing argument is never marked'
        !(arguments.upper instanceof WildcardArgument)
        !(arguments.nested instanceof WildcardArgument)
    }

    void 'a wildcard keeps the type arguments of its bound'() {
        given:
        Argument<?> comparable = constructorArguments().parameterizedBound.typeParameters[0]

        expect:
        comparable.type == Comparable
        upper(comparable) == [comparable.type.name] && lower(comparable) == []
        comparable.typeParameters[0].type == String
        !(comparable.typeParameters[0] instanceof WildcardArgument)
        wildcard(comparable).upperBounds[0].typeParameters[0].type == String
    }

    void 'a nested wildcard is recorded at its own level only'() {
        given:
        Map<String, Argument<?>> arguments = constructorArguments()

        expect:
        !(arguments.nested.typeParameters[0] instanceof WildcardArgument)
        arguments.nested.typeParameters[0].type == List
        arguments.nested.typeParameters[0].typeParameters[0].type == Number
        upper(arguments.nested.typeParameters[0].typeParameters[0]) == [Number.name] && lower(arguments.nested.typeParameters[0].typeParameters[0]) == []

        and: 'a wildcard bounded by a type with a wildcard records both'
        Argument<?> foo = arguments.optionalNested.typeParameters[0]
        foo.type.name == 'test.Foo'
        upper(foo) == [foo.type.name] && lower(foo) == []
        foo.typeParameters[0].type == Object
        upper(foo.typeParameters[0]) == ['java.lang.Object'] && lower(foo.typeParameters[0]) == []
    }

    void 'each type argument is recorded independently'() {
        given:
        Argument<?> two = constructorArguments().two

        expect:
        two.typeParameters.length == 2
        two.typeParameters[0].type == CharSequence
        upper(two.typeParameters[0]) == [CharSequence.name] && lower(two.typeParameters[0]) == []
        two.typeParameters[1].type.name == 'test.Book'
        lower(two.typeParameters[1]) == ['test.Book'] && upper(two.typeParameters[1]) == ['java.lang.Object']

        and: 'by name'
        upper(two.typeVariables.K) == [CharSequence.name] && lower(two.typeVariables.K) == []
        lower(two.typeVariables.V) == ['test.Book'] && upper(two.typeVariables.V) == ['java.lang.Object']
    }

    void 'a wildcard inside an array argument is recorded'() {
        given:
        Argument<?> array = constructorArguments().array

        expect:
        array.type == List[]
        array.typeParameters[0].type == Number
        upper(array.typeParameters[0]) == [Number.name] && lower(array.typeParameters[0]) == []
    }

    void 'an argument with a nested wildcard has the same type as the argument with the type it is bounded by'() {
        given: 'the signature the Scala compiler writes for a Map[String, ? <: AnyRef] parameter'
        BeanDefinition<?> client = buildBeanDefinition('test.HeadersClient', '''
package test

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import java.util.List
import java.util.Map

@Singleton
class HeadersClient {
  @Executable
  def send(headers: Map[String, ? <: Object], nested: List[Map[String, ? <: Number]], plain: Map[String, Object]): Unit = ()
}
''')
        Argument<?>[] arguments = client.executableMethods.find { it.methodName == 'send' }.arguments
        Argument<?> headers = arguments[0]
        Argument<?> nested = arguments[1]
        Argument<?> plain = arguments[2]

        expect:
        headers.typeParameters[1] instanceof WildcardArgument
        headers.equalsType(Argument.mapOf(String, Object))
        Argument.mapOf(String, Object).equalsType(headers)
        headers.typeHashCode() == Argument.mapOf(String, Object).typeHashCode()
        headers.equalsType(plain) && plain.equalsType(headers)
        headers.typeHashCode() == plain.typeHashCode()
        !headers.equalsType(Argument.mapOf(String, String))

        and: 'at any depth, the map named after the type parameter of List it stands for'
        nested.equalsType(Argument.listOf(Argument.mapOf(String, Number).withName('E')))
        nested.typeHashCode() == Argument.listOf(Argument.mapOf(String, Number).withName('E')).typeHashCode()

        and: 'equals still tells the wildcard apart'
        headers.typeParameters[1] != plain.typeParameters[1]
        plain.typeParameters[1] != headers.typeParameters[1]
    }

    void 'a wildcard is recorded for an injected property'() {
        given: 'an injected var is injected through its setter, as a Kotlin property is'
        Argument<?> field = injected('field')
        Argument<?> mapField = injected('mapField')

        expect:
        upper(field.typeParameters[0]) == ['java.lang.Object'] && lower(field.typeParameters[0]) == []
        upper(mapField.typeParameters[0]) == [CharSequence.name] && lower(mapField.typeParameters[0]) == []
        lower(mapField.typeParameters[1]) == ['test.Book'] && upper(mapField.typeParameters[1]) == ['java.lang.Object']
    }

    void 'a wildcard is recorded for an injected method parameter'() {
        given:
        def inject = definition.injectedMethods.find { it.name == 'inject' }
        Map<String, Argument<?>> arguments = inject.arguments.collectEntries { [it.name, it] }

        expect:
        upper(arguments.injected.typeParameters[0]) == ['java.lang.Object'] && lower(arguments.injected.typeParameters[0]) == []
        arguments.lowerInjected.typeParameters[0].type.name == 'test.Book'
        lower(arguments.lowerInjected.typeParameters[0]) == ['test.Book'] && upper(arguments.lowerInjected.typeParameters[0]) == ['java.lang.Object']
    }

    void 'a wildcard is recorded for an executable method parameter'() {
        given:
        Map<String, Argument<?>> arguments = method('on').arguments.collectEntries { [it.name, it] }

        expect:
        upper(arguments.unbounded.typeParameters[0]) == ['java.lang.Object'] && lower(arguments.unbounded.typeParameters[0]) == []
        arguments.upper.typeParameters[0].type == Number
        upper(arguments.upper.typeParameters[0]) == [Number.name] && lower(arguments.upper.typeParameters[0]) == []
        arguments.lower.typeParameters[0].type.name == 'test.Book'
        lower(arguments.lower.typeParameters[0]) == ['test.Book'] && upper(arguments.lower.typeParameters[0]) == ['java.lang.Object']
    }

    void 'a wildcard bounded by a method type variable is recorded with the variable bound'() {
        given:
        Map<String, Argument<?>> arguments = method('methodVariable').arguments.collectEntries { [it.name, it] }

        expect:
        arguments.methodVariableBound.typeParameters[0].type == Number
        upper(arguments.methodVariableBound.typeParameters[0]) == [Number.name] && lower(arguments.methodVariableBound.typeParameters[0]) == []

        and: 'the method type variable itself is not'
        arguments.variable.typeParameters[0].type == Number
        arguments.variable.typeParameters[0].isTypeVariable()
        !(arguments.variable.typeParameters[0] instanceof WildcardArgument)
    }

    void 'a wildcard is recorded for an executable method return type'() {
        given:
        Argument<?> returnedUpper = method('returnsUpper').returnType.asArgument()
        Argument<?> two = method('returnsTwo').returnType.asArgument()
        Argument<?> obj = method('returnsObject').returnType.asArgument()

        expect:
        returnedUpper.typeParameters[0].type == Number
        upper(returnedUpper.typeParameters[0]) == [Number.name] && lower(returnedUpper.typeParameters[0]) == []
        upper(two.typeParameters[0]) == [CharSequence.name] && lower(two.typeParameters[0]) == []
        lower(two.typeParameters[1]) == ['test.Book'] && upper(two.typeParameters[1]) == ['java.lang.Object']
        !(obj.typeParameters[0] instanceof WildcardArgument)
    }

    private io.micronaut.inject.ExecutableMethod<?, ?> method(String name) {
        definition.executableMethods.find { it.methodName == name }
    }

    private Argument<?> injected(String property) {
        def field = definition.injectedFields.find { it.name == property }
        if (field != null) {
            return field.asArgument()
        }
        definition.injectedMethods.find { it.name == property + '_$eq' }.arguments[0]
    }
}
