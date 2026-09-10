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
package io.micronaut.docs.data

import io.micronaut.context.ApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A Micronaut Data repository written in Scala, against H2 in memory.
 *
 * <p>Micronaut Data reads more of the Element API than anything else Micronaut ships. A
 * repository is introduction advice over an abstract type, so there is no implementation to run
 * unless the plugin produced one; the queries are derived from method names and from resolved
 * return types, so the generics of a parameterized classpath supertype have to be right; and the
 * entity is mapped from a case class, so annotations written on class parameters have to reach
 * the properties. Any of those being wrong stops the build or produces a repository that returns
 * the wrong thing.</p>
 *
 * <p>Running the queries rather than only compiling them is the point. Compilation proves the
 * metadata was accepted; only executing proves the SQL that was generated matches the entity
 * that was mapped.</p>
 */
class BookRepositorySpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run([
        'datasources.default.url'              : 'jdbc:h2:mem:books;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
        'datasources.default.username'         : 'sa',
        'datasources.default.password'         : '',
        'datasources.default.driver-class-name': 'org.h2.Driver',
        'datasources.default.schema-generate'  : 'CREATE_DROP',
        'datasources.default.dialect'          : 'H2'
    ])

    @Shared
    BookRepository repository = context.getBean(BookRepository)

    void setup() {
        repository.deleteAll()
    }

    void "the repository is introduction advice over a trait with no implementation"() {
        expect: 'a bean exists for a type that declares no method body anywhere'
        repository != null
        BookRepository.isInterface() || java.lang.reflect.Modifier.isAbstract(BookRepository.modifiers)
        repository.getClass() != BookRepository
    }

    void "saves a case class entity and reads back the generated identifier"() {
        when: '''the identifier is zero on the way in, because the database assigns it. A Scala
                 Long is the primitive, so zero rather than null is what "not yet saved" looks
                 like -- the same convention a Java entity with a `long` identifier uses'''
        def saved = repository.save(new Book(0L, 'Dune', 412))

        then: '''save returns the entity type rather than something unresolved, which is what
                 the generic signature of CrudRepository promises'''
        saved.id() != 0L
        saved.title() == 'Dune'
        saved.pages() == 412

        and: 'and it can be read back by that identifier'
        def found = repository.findById(saved.id())
        found.present
        found.get().title() == 'Dune'
    }

    void "derives a query from a method name, returning a Scala collection"() {
        given:
        repository.saveAll([new Book(0L, 'Dune', 412), new Book(0L, 'Dune', 500), new Book(0L, 'Emma', 474)])

        when:
        def found = repository.findByTitle('Dune')

        then: '''the return type is Scala's own List, not java.util.List. Micronaut Data asks
                 whether the declared type stands in for an Iterable, and the answer for a Scala
                 collection is what micronaut-runtime-scala's converters make true'''
        found.getClass().name.startsWith('scala.collection.')

        and: 'the derived query filtered on the mapped property'
        found.size() == 2
        scala.jdk.javaapi.CollectionConverters.asJavaCollection(found).every { it.title() == 'Dune' }
    }

    void "derives a projection from a method name"() {
        given:
        repository.saveAll([new Book(0L, 'Dune', 412), new Book(0L, 'Emma', 474), new Book(0L, 'Ulysses', 730)])

        expect: 'the comparison is applied in SQL, not in memory'
        repository.countByPagesGreaterThan(450) == 2
        repository.countByPagesGreaterThan(1000) == 0
    }

    void "inherits the whole CrudRepository contract"() {
        given: '''every one of these is declared on the parameterized classpath supertype, so
                  each needed its type variables resolved before Data would generate it'''
        repository.saveAll([new Book(0L, 'Dune', 412), new Book(0L, 'Emma', 474)])

        expect:
        repository.count() == 2
        repository.findAll().size() == 2
        repository.findAll().every { it.id() != 0L }

        when: 'deleteAll takes an Iterable of a wildcard bounded by the entity type'
        repository.deleteAll(repository.findAll())

        then:
        repository.count() == 0
    }
}
