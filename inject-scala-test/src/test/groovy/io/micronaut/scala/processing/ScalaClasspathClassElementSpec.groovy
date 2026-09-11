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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler

import java.nio.file.Path

/**
 * A Scala type the compiler reads from the classpath is described the way the same type is
 * described from source.
 *
 * <p>This is the shape of every incremental build. Gradle and sbt recompile the files that
 * changed, and everything those files refer to is read back from class files and TASTy. Any
 * difference between the model of a source type and the model of the same type read that way
 * is a build that passes clean and fails on the next edit -- which is how this was found: a
 * whitespace change to a repository made Micronaut Data report the entity's properties as
 * {@code _1, _2, _3}, the tuple accessors a case class compiles to, because the compiled class
 * was being read with Java's conventions and a Scala accessor follows none of them.</p>
 *
 * <p>The compiler already has the answer. Every classpath Scala type it touches is unpickled
 * from TASTy into the same symbols a source type has, flags and all, so the model is built from
 * those rather than from anything that has to guess what the bytecode meant.</p>
 */
class ScalaClasspathClassElementSpec extends AbstractScalaTypeElementSpec {

    private static final String BOOK = '''
package library

import jakarta.inject.Named

/** A book. */
case class Book(@Named("key") id: Long, title: String, pages: Int = 100):
  def summary: String = s"$title ($pages)"
'''

    private static final String SHELF = '''
package shelf

import library.Book

class Shelf:
  def book: Book = null
'''

    void "a case class compiled in an earlier run has the properties it declared"() {
        given:
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Book', BOOK))
        ClassElement book = returnTypeOfBook(library)

        expect: 'the properties are the class parameters, not the tuple accessors the case class compiles to'
        book.beanProperties*.name == ['id', 'title', 'pages']

        and: 'at their Scala types, primitives included'
        book.beanProperties.find { it.name == 'id' }.type.name == 'long'
        book.beanProperties.find { it.name == 'title' }.type.name == 'java.lang.String'
        book.beanProperties.find { it.name == 'pages' }.type.name == 'int'

        and: 'the ordinary method is a method and not a property'
        !book.beanProperties.any { it.name == 'summary' }
        book.getEnclosedElements(ElementQuery.ALL_METHODS.named('summary')).size() == 1
    }

    void "an annotation on a class parameter reaches the property of a classpath type"() {
        given: '''the same rule as for a source type: a class parameter is where a Scala property
                  is declared, so what is written there belongs to the property'''
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Book', BOOK))
        ClassElement book = returnTypeOfBook(library)

        expect:
        book.beanProperties.find { it.name == 'id' }.stringValue('jakarta.inject.Named').get() == 'key'
        book.beanProperties.find { it.name == 'title' }.getAnnotationNames().isEmpty()
    }

    void "a constructor parameter default survives the classpath"() {
        given:
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Book', BOOK))
        ClassElement book = returnTypeOfBook(library)
        def constructor = book.primaryConstructor.get()

        expect:
        constructor.parameters*.name == ['id', 'title', 'pages']
        !constructor.parameters[1].hasDefault()
        constructor.parameters[2].hasDefault()
    }

    void "a type read from the classpath and the same type read from source agree"() {
        given: 'the source model is the reference; the classpath model has to say the same things'
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Book', BOOK))
        ClassElement fromClasspath = returnTypeOfBook(library)
        ClassElement fromSource = buildClassElement('library.Book', BOOK)

        expect:
        describe(fromClasspath) == describe(fromSource)
    }

    void "a trait compiled earlier contributes its methods to a source implementation"() {
        given:
        Path library = precompile(
            ScalaCompiler.SourceFile.scala('library.Book', BOOK),
            ScalaCompiler.SourceFile.scala('library.Catalogue', '''
package library

trait Catalogue:
  def find(title: String): Book
  def count: Int = 0
''')
        )
        ClassElement impl = buildClassElementAgainst([library], 'shelf.LocalCatalogue', '''
package shelf

import library.{Book, Catalogue}

class LocalCatalogue extends Catalogue:
  def find(title: String): Book = null
''')

        when:
        def find = impl.getEnclosedElements(ElementQuery.ALL_METHODS.named('find'))[0]
        def count = impl.getEnclosedElements(ElementQuery.ALL_METHODS.named('count'))[0]

        then: 'the inherited concrete method is there, declared by the trait'
        count.declaringType.name == 'library.Catalogue'
        count.returnType.name == 'int'

        and: 'and the parameter is named as written, not as the JVM numbers it'
        find.parameters*.name == ['title']
        find.returnType.beanProperties*.name == ['id', 'title', 'pages']
    }

    void "a Java annotation on a classpath type carries the stereotypes it carries from source"() {
        given: '''Micronaut asks for stereotypes far more often than for the annotation itself
                  -- Micronaut Data recognises an entity by @MappedEntity and then treats it as
                  introspected by the stereotype on that annotation. An annotation without its
                  stereotypes is recognised by nothing, so the classpath model has to carry the
                  same ones the source model does. @Prototype is used because no visitor adds to
                  it; a visitor runs over source types only, so what one adds is legitimately
                  absent from a classpath type, here as under javac'''
        String source = '''
package library

import io.micronaut.context.annotation.Prototype

@Prototype
class Settings:
  var name: String = ""
'''
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Settings', source))
        ClassElement fromClasspath = buildClassElementAgainst([library], 'shelf.Uses', '''
package shelf

class Uses:
  def settings: library.Settings = null
''').getEnclosedElements(ElementQuery.ALL_METHODS.named('settings'))[0].returnType
        ClassElement fromSource = buildClassElement('library.Settings', source)

        expect:
        fromClasspath.annotationNames.sort() == fromSource.annotationNames.sort()
        fromClasspath.stereotypeAnnotationNames.sort() == fromSource.stereotypeAnnotationNames.sort()
        fromClasspath.hasStereotype('io.micronaut.context.annotation.Bean')
        fromClasspath.hasStereotype('jakarta.inject.Scope')

        and: 'and the property, which is a var in the body rather than a class parameter'
        fromClasspath.beanProperties*.name == ['name']
        !fromClasspath.beanProperties[0].readOnly
    }

    void "a Java supertype's type variables resolve to a classpath entity as they do to a source one"() {
        given: '''this is the shape of a Micronaut Data repository: the entity is a classpath
                  type, the repository is source, and the methods are inherited from a Java
                  interface whose own type variables -- `<S extends E> Iterable<S> updateAll(
                  Iterable<S>)` -- have to resolve through E to the entity. Data decides whether
                  a method is an entity update by asking exactly these questions of the parameter'''
        String entity = '''
package library

import io.micronaut.data.annotation.{GeneratedValue, Id, MappedEntity}

@MappedEntity
case class Book(@Id @GeneratedValue id: Long, title: String, pages: Int)
'''
        String repository = '''
package shelf

import io.micronaut.data.repository.CrudRepository
import library.Book

trait BookRepository extends CrudRepository[Book, java.lang.Long]:
  def findByTitle(title: String): List[Book]
'''
        Path library = precompile(ScalaCompiler.SourceFile.scala('library.Book', entity))
        ClassElement fromClasspath = buildClassElementAgainst([library], 'shelf.BookRepository', repository)
        ClassElement fromSource = buildClassElement([
            ScalaCompiler.SourceFile.scala('library.Book', entity),
            ScalaCompiler.SourceFile.scala('shelf.BookRepository', repository)
        ], 'shelf.BookRepository')

        expect:
        describeRepository(fromClasspath) == describeRepository(fromSource)
        describeRepository(fromClasspath).updateAllParameter.entity
    }

    private static Map describeRepository(ClassElement repository) {
        def updateAll = repository.getEnclosedElements(ElementQuery.ALL_METHODS.named('updateAll'))[0]
        def entities = updateAll.parameters[0].genericType
        def entity = entities.firstTypeArgument.orElse(null)
        [
            updateAllParameter: [
                type        : entities.name,
                iterable    : entities.isAssignable(Iterable),
                argument    : entity?.name,
                entity      : entity?.hasStereotype('io.micronaut.data.annotation.MappedEntity'),
                introspected: entity?.hasStereotype('io.micronaut.core.annotation.Introspected'),
                properties  : entity?.beanProperties*.name
            ],
            updateAllReturn   : updateAll.genericReturnType.firstTypeArgument.map { it.name }.orElse(null),
            findById          : repository.getEnclosedElements(ElementQuery.ALL_METHODS.named('findById'))[0].genericReturnType.firstTypeArgument.map { it.name }.orElse(null),
            save              : repository.getEnclosedElements(ElementQuery.ALL_METHODS.named('save'))[0].genericReturnType.name,
        ]
    }

    void "a generic Scala supertype compiled earlier has its type variables bound by the subtype"() {
        given: '''the same shape as CrudRepository, in Scala: a library trait parameterized by
                  the entity, and a repository in the next module binding it'''
        Path library = precompile(
            ScalaCompiler.SourceFile.scala('library.Book', BOOK),
            ScalaCompiler.SourceFile.scala('library.Repo', '''
package library

trait Repo[E, ID]:
  def find(id: ID): E
  def all: List[E]
  def save[S <: E](entity: S): S
''')
        )
        ClassElement repository = buildClassElementAgainst([library], 'shelf.BookRepo', '''
package shelf

import library.{Book, Repo}

trait BookRepo extends Repo[Book, java.lang.Long]:
  def findByTitle(title: String): List[Book]
''')

        expect:
        method(repository, 'find').genericReturnType.name == 'library.Book'
        method(repository, 'find').parameters[0].genericType.name == 'java.lang.Long'
        method(repository, 'all').genericReturnType.firstTypeArgument.get().name == 'library.Book'
        method(repository, 'save').genericReturnType.name == 'library.Book'

        and: 'and the same when the trait is reached as a plain parameterized reference'
        ClassElement reference = buildClassElementAgainst([library], 'shelf.Uses', '''
package shelf

class Uses:
  def repo: library.Repo[library.Book, java.lang.Long] = null
''').getEnclosedElements(ElementQuery.ALL_METHODS.named('repo'))[0].genericReturnType
        method(reference, 'find').genericReturnType.name == 'library.Book'
        method(reference, 'all').genericReturnType.firstTypeArgument.get().name == 'library.Book'
    }

    void "a Java class is modelled from the compiler's symbols, with the parameter annotations the compiler skips"() {
        given: '''dotty's reading of a Java class file drops every annotation on a parameter -- a TODO
                  in its ClassfileParser -- and never reads type annotations, which is where
                  jspecify puts nullability. TextPlainCodec's constructor has both kinds:
                  `@Value("...") Optional<Charset>`, `@Named("text") @Nullable CodecConfiguration`'''
        ClassElement uses = buildClassElement('shelf.Uses', '''
package shelf

class Uses:
  def codec: io.micronaut.runtime.http.codec.TextPlainCodec = null
  def request: io.micronaut.http.MutableHttpRequest[String] = null
''')
        ClassElement codec = method(uses, 'codec').returnType
        def constructor = codec.primaryConstructor.get()
        def body = method(method(uses, 'request').returnType, 'body')

        expect: 'the constructor and the annotation the compiler does read'
        constructor.parameters*.name == ['defaultCharset', 'codecConfiguration', 'conversionService']
        constructor.hasAnnotation('jakarta.inject.Inject')

        and: 'the parameter annotations, with their values'
        constructor.parameters[0].stringValue('io.micronaut.context.annotation.Value').get() == '${micronaut.application.default-charset}'
        constructor.parameters[1].stringValue('jakarta.inject.Named').get() == 'text'
        constructor.parameters[2].annotationNames.isEmpty()

        and: 'and the type-use nullability, which is what Argument.isNullable reads'
        constructor.parameters[1].isNullable()
        !constructor.parameters[0].isNullable()

        and: '''the same on an interface method whose only annotation is a type annotation:
                 `<T> MutableHttpRequest<T> body(@Nullable T body)`'''
        body.parameters[0].isNullable()
        body.parameters[0].genericType.isTypeVariable()
        body.genericReturnType.name == 'io.micronaut.http.MutableHttpRequest'
    }

    void "a Java class's static members, enum constants and varargs are what the class file says"() {
        given:
        ClassElement uses = buildClassElement('shelf.Uses', '''
package shelf

class Uses:
  def duration: java.time.Duration = null
  def day: java.time.DayOfWeek = null
  def text: String = null
  def locale: java.util.Locale = null
''')
        ClassElement duration = method(uses, 'duration').returnType
        ClassElement day = method(uses, 'day').returnType
        ClassElement text = method(uses, 'text').returnType
        ClassElement locale = method(uses, 'locale').returnType

        expect: 'a static factory is a static method of the class, as it is in the class file'
        def ofSeconds = duration.getEnclosedElements(ElementQuery.ALL_METHODS.named('ofSeconds'))
        ofSeconds.size() == 2
        ofSeconds.every { it.static }
        ofSeconds.find { it.parameters.size() == 1 }.parameters[0].type.name == 'long'

        and: 'a Java enum is an enum, with its constants'
        day.isEnum()
        day.getEnclosedElements(ElementQuery.ALL_FIELDS.includeEnumConstants().named('MONDAY')).size() == 1
        day.getEnclosedElements(ElementQuery.ALL_METHODS.named('valueOf')).size() == 1
        day.getEnclosedElements(ElementQuery.ALL_METHODS.named('values')).size() == 1

        and: 'a varargs parameter is the array it compiles to'
        def format = text.getEnclosedElements(ElementQuery.ALL_METHODS.named('format')).find { it.parameters.size() == 2 }
        format.static
        format.parameters[1].type.name == 'java.lang.Object'
        format.parameters[1].type.isArray()

        and: 'bean properties follow Java conventions, since the class has no Scala ones'
        locale.beanProperties*.name.containsAll(['language', 'country'])
    }

    private static def method(ClassElement element, String name) {
        element.getEnclosedElements(ElementQuery.ALL_METHODS.named(name))[0]
    }

    private ClassElement returnTypeOfBook(Path library) {
        ClassElement shelf = buildClassElementAgainst([library], 'shelf.Shelf', SHELF)
        shelf.getEnclosedElements(ElementQuery.ALL_METHODS.named('book'))[0].returnType
    }

    /**
     * What a consumer sees of a type, as a comparable value. Only the things the two models
     * can both know are included: a class file has no doc comment and no constant expression.
     */
    private static Map describe(ClassElement element) {
        [
            name        : element.name,
            annotations : element.annotationNames.sort(),
            properties  : element.beanProperties.collect {
                [name: it.name, type: it.type.name, readOnly: it.readOnly, annotations: it.annotationNames.sort()]
            },
            constructor : element.primaryConstructor.get().parameters.collect {
                [name: it.name, type: it.type.name, hasDefault: it.hasDefault(), annotations: it.annotationNames.sort()]
            },
            methods     : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).collect { it.name }.sort()
        ]
    }
}
