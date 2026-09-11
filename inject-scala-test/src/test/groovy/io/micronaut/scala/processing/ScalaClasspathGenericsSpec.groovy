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

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Generic signatures inherited from a supertype that was compiled earlier.
 *
 * <p>Extending a parameterized interface from a library is the ordinary way to use one, and what
 * its methods take and return is the point of doing so. Every inherited signature used to come
 * back at its erasure, because only the supertype's name reached the classpath element and not
 * what it was parameterized with, so {@code findAll} reported {@code List<Object>} and
 * {@code save} reported {@code Object}. Micronaut Data refuses a repository whose save method
 * returns something it cannot identify, which is how this was found.</p>
 *
 * <p>{@code CrudRepository} is the fixture rather than something invented here because it has
 * the three shapes that matter and are awkward to reproduce: a variable used bare, a variable
 * nested inside another type, and a method that declares its own variable in terms of the
 * class's -- {@code <S extends E> S save(S)}, where the bound is the only link between the two
 * and erases to {@code Object}.</p>
 */
class ScalaClasspathGenericsSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package generics

import io.micronaut.data.repository.CrudRepository

case class Book(id: java.lang.Long, title: String)

trait BookRepository extends CrudRepository[Book, java.lang.Long]
'''

    private static Object method(element, String name) {
        element.getEnclosedElements(ElementQuery.ALL_METHODS.named(name))[0]
    }

    /** {@code deleteAll} is overloaded; this is the one that takes the entities. */
    private static Object deleteAllOfIterable(element) {
        element.getEnclosedElements(ElementQuery.ALL_METHODS.named('deleteAll'))
            .find { it.parameters.length == 1 }
    }

    private static String describe(type) {
        def arguments = type.typeArguments.collect { name, argument -> argument.name }
        arguments.isEmpty() ? type.name : "${type.name}<${arguments.join(',')}>"
    }

    void "resolves the entity type in inherited return types"() {
        given:
        def element = buildClassElement('generics.BookRepository', SOURCE)

        expect: 'a variable nested inside the returned type'
        describe(method(element, 'findAll').genericReturnType) == 'java.util.List<generics.Book>'
        describe(method(element, 'findById').genericReturnType) == 'java.util.Optional<generics.Book>'

        and: '''a method's own variable, declared `<S extends E>`, which is only an entity once
                the bound is followed to E and E to what the class bound it to'''
        describe(method(element, 'save').genericReturnType) == 'generics.Book'
        describe(method(element, 'saveAll').genericReturnType) == 'java.util.List<generics.Book>'

        and: 'and a signature naming no variable is untouched'
        describe(method(element, 'count').genericReturnType) == 'long'
    }

    void "resolves the entity type in inherited parameters"() {
        given: 'the query a repository method becomes is derived from these as much as the return'
        def element = buildClassElement('generics.BookRepository', SOURCE)

        expect:
        describe(method(element, 'save').parameters[0].genericType) == 'generics.Book'
        describe(method(element, 'saveAll').parameters[0].genericType) == 'java.lang.Iterable<generics.Book>'

        and: 'a wildcard bounded by the variable resolves to what the bound resolves to'
        describe(deleteAllOfIterable(element).parameters[0].genericType) == 'java.lang.Iterable<generics.Book>'

        and: 'and the identifier variable stays distinct from the entity variable'
        describe(method(element, 'deleteById').parameters[0].genericType) == 'java.lang.Long'
    }

    void "leaves a variable alone when the supertype is used raw"() {
        given: '''nothing binds it, so collapsing it to its bound would rewrite the signature --
                  which is what an @Adapter is generated against'''
        def element = buildClassElement('generics.Raw', '''
package generics

import java.util.function.Function

trait Raw extends Function[String, Integer]
''')

        expect:
        method(element, 'apply').genericReturnType.name == 'java.lang.Integer'
        method(element, 'apply').parameters[0].genericType.name == 'java.lang.String'
    }
}
