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
import io.micronaut.inject.ast.EnumElement
import io.micronaut.inject.ast.MethodElement
import io.micronaut.inject.ast.ParameterElement
import io.micronaut.inject.ast.TypedElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler
import spock.lang.Unroll

import java.nio.file.Path

/**
 * The same Scala declaration, read from source and read from the classpath, is one model.
 *
 * <p>A classpath type is built from the symbols the compiler unpickles from TASTy, a source
 * type from the trees it just typed. The two are meant to be the same code applied to the
 * same flags, and this is where that is checked -- exhaustively rather than for the case that
 * happened to fail, since the gap only ever shows on the next incremental build. Everything
 * an element reports is described as plain data for a library of declarations, and the two
 * descriptions have to match; only what a class file cannot hold -- constant expressions and
 * doc comments -- is left out.</p>
 *
 * <p>The source description is the reference. When they disagree it is the classpath model
 * that is wrong, unless the source model is being corrected on purpose, in which case both
 * change and this spec says so.</p>
 */
class ScalaClasspathParitySpec extends AbstractScalaTypeElementSpec {

    private static final Map<String, String> LIBRARY = [
        'library.Shapes': '''
package library

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Prototype
import io.micronaut.core.annotation.Creator
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.Nullable
import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Singleton

/** A case class with defaults, annotations on parameters, and members in the body. */
@Introspected
case class Book(@Named("key") id: Long, title: String, pages: Int = 100, tags: List[String] = Nil):
  def summary: String = s"$title ($pages)"
  val shelf: String = "A"
  var loans: Int = 0

/** A plain class with every kind of member and visibility. */
@Prototype
class Plain(val name: String, private val secret: String, protected val shared: Int):
  var count: Int = 0
  private var hidden: Int = 1
  @Inject var injected: Book = null
  def compute(x: Int, y: Int = 2): Int = x + y
  final def fixed: String = name
  protected def guarded(): Unit = ()
  private def internal(): Unit = ()
  @throws[java.io.IOException]
  def risky(): Unit = ()

object Plain:
  @Creator
  def of(name: String): Plain = Plain(name, "s", 1)
  val Default: Plain = of("default")

/** A trait with abstract and concrete members. */
trait Catalogue[E, ID]:
  def find(id: ID): E
  def all: List[E]
  def count: Int = 0
  def save[S <: E](entity: S): S

/** A trait whose method type parameter is bounded by a bounded class type parameter. */
trait Store[E <: AnyRef]:
  def put[S <: E](entity: S): S

/** An abstract class with a constructor of its own and state to inject. */
abstract class Base(val label: String):
  @Inject var dependency: Book = null
  def describe: String
  def prefix: String = "base:"

/** A simple Scala 3 enum and an enum with fields. */
enum Colour:
  case Red, Green, Blue

enum Planet(val mass: Double):
  case Mercury extends Planet(3.3e23)
  case Earth extends Planet(5.97e24)

/** A value class, which erases to its underlying type where it is used at the top level. */
final class UserId(val value: String) extends AnyVal

/** Generic signatures of every kind. */
class Generics[T <: AnyRef](val value: T):
  def wrap(items: java.util.List[? <: Number]): java.util.Map[String, ? >: Integer] = null
  def lazily(compute: => T): T = compute
  def many(xs: String*): Int = xs.size
  def option(id: UserId): Option[T] = None
  def matrix(rows: Array[Array[Int]]): Array[String] = Array.empty
  def nested(x: Map[String, List[Option[T]]]): Unit = ()
  def typed[A <: Comparable[A]](a: A): A = a

/** A sealed hierarchy and nesting. */
sealed trait Shape
final case class Circle(radius: Double) extends Shape
case object Origin extends Shape

class Outer:
  class Inner(val n: Int)
  object Companion:
    class Deep

/** Type-use annotations, which TASTy keeps. */
class Annotated:
  def maybe(input: String @Nullable): String @Nullable = input
  var field: String @Nullable = null

/**
 * Not `@ConfigurationProperties`: core's visitor annotates a source configuration class with
 * a computed `@ConfigurationReader`, and visitors run over source types only, under javac as
 * here. Binding across the classpath is asserted end to end instead.
 */
class Settings:
  var host: String = "localhost"
  var port: Int = 8080
  var timeout: Option[java.time.Duration] = None

/** An annotated object is a bean factory, and its model says so on both sides. */
@Singleton
object Registry:
  val Root: String = "/"
  def lookup(key: String): Option[String] = None
''',
    ]

    private static final List<String> TYPES = [
        'library.Book', 'library.Plain', 'library.Catalogue', 'library.Store', 'library.Base',
        'library.Colour', 'library.Planet', 'library.UserId', 'library.Generics', 'library.Shape',
        'library.Circle', 'library.Outer', 'library.Outer$Inner', 'library.Annotated', 'library.Settings',
        'library.Registry$',
    ]

    @Unroll
    void "#type is described the same from source and from the classpath"() {
        given:
        def fromSource = sourceElements()[type]
        def fromClasspath = classpathElements()[type]

        expect:
        fromSource != null
        fromClasspath != null
        differences(describe(fromSource), describe(fromClasspath), '') == []

        where:
        type << TYPES
    }

    void "an unannotated companion object is a bare reference on both sides"() {
        given: '''an unannotated object is not modelled from source -- it declares no beans --
                  and the classpath model has to say the same, or every library's companion
                  object would arrive as a @Factory @Singleton'''
        def fromSource = sourceElements()['library.Plain$']
        def fromClasspath = classpathElements()['library.Plain$']

        expect:
        fromSource != null
        fromClasspath != null
        differences(describe(fromSource), describe(fromClasspath), '') == []
    }

    /** Where two descriptions disagree, as `path: source=... classpath=...` lines. */
    static List<String> differences(Object source, Object classpath, String path) {
        if (source instanceof Map && classpath instanceof Map) {
            return (source.keySet() + classpath.keySet()).toList().unique().sort().collectMany { key ->
                differences(source[key], classpath[key], "${path}/${key}".toString())
            }
        }
        if (source instanceof List && classpath instanceof List && source.size() == classpath.size()) {
            return (0..<source.size()).collectMany { i -> differences(source[i], classpath[i], "${path}[${i}]".toString()) }
        }
        source == classpath ? [] : ["${path}: source=${source} classpath=${classpath}".toString()]
    }

    private static Map<String, ClassElement> sourceElementsCache
    private static Map<String, ClassElement> classpathElementsCache

    private Map<String, ClassElement> sourceElements() {
        if (sourceElementsCache == null) {
            List<ClassElement> elements = []
            ScalaCompiler.compile(librarySources() + [ScalaCompiler.SourceFile.scala('probe.Uses', usesSource())], List.of(),
                { ClassElement element -> elements.add(element) } as java.util.function.Consumer<ClassElement>)
            // Both sides are reached the same way, through the probe's return types, so that a
            // parameterised reference is compared with a parameterised reference and a module
            // class or inner class -- which are not visited on their own -- can be reached at all.
            def uses = elements.find { it.name == 'probe.Uses' }
            sourceElementsCache = (TYPES + ['library.Plain$']).collectEntries { [(it): probeType(uses, it)] }
        }
        sourceElementsCache
    }

    private Map<String, ClassElement> classpathElements() {
        if (classpathElementsCache == null) {
            Path library = precompile(*librarySources())
            def uses = buildClassElementAgainst([library], 'probe.Uses', usesSource())
            classpathElementsCache = (TYPES + ['library.Plain$']).collectEntries { [(it): probeType(uses, it)] }
        }
        classpathElementsCache
    }

    private static List<ScalaCompiler.SourceFile> librarySources() {
        LIBRARY.collect { name, text -> ScalaCompiler.SourceFile.scala(name, text) }
    }

    /** How each type is written where it is used, when that differs from its binary name. */
    private static final Map<String, String> SPELLING = [
        'library.Plain$'     : 'library.Plain.type',
        'library.Registry$'  : 'library.Registry.type',
        'library.Outer$Inner': 'library.Outer#Inner',
        'library.Generics'   : 'library.Generics[String]',
        'library.Catalogue'  : 'library.Catalogue[String, Int]',
        'library.Store'      : 'library.Store[String]',
    ]

    /** One method per type, so every type is reachable as a return type from one probe class. */
    private static String usesSource() {
        def methods = (TYPES + ['library.Plain$']).withIndex().collect { name, i ->
            "  def t${i}: ${SPELLING.getOrDefault(name, name)} = ???"
        }
        "package probe\n\nclass Uses:\n${methods.join('\n')}\n"
    }

    private static ClassElement probeType(ClassElement uses, String name) {
        def index = (TYPES + ['library.Plain$']).indexOf(name)
        uses.getEnclosedElements(ElementQuery.ALL_METHODS.named("t${index}".toString()))[0].returnType
    }

    /**
     * Everything an element reports, as comparable data. Members are sorted so that order,
     * which neither model promises, does not count as a difference.
     */
    static Map describe(ClassElement element) {
        [
            name        : element.name,
            canonical   : element.canonicalName,
            simple      : element.simpleName,
            kind        : [interface: element.isInterface(), enum: element.isEnum(), abstract: element.isAbstract(),
                           final: element.isFinal(), inner: element.isInner(), record: element.isRecord()],
            modifiers   : element.modifiers*.name().sort(),
            annotations : annotations(element),
            stereotypes : element.stereotypeAnnotationNames.sort(),
            typeParams  : element.declaredGenericPlaceholders.collect { [name: it.variableName, bounds: it.bounds*.name] },
            superType   : element.superType.map { it.name }.orElse(null),
            interfaces  : element.interfaces*.name.sort(),
            constructors: element.getEnclosedElements(ElementQuery.CONSTRUCTORS).collect { method(it) }.sort { it.toString() },
            methods     : element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).collect { method(it) }.sort { it.toString() },
            inherited   : element.getEnclosedElements(ElementQuery.ALL_METHODS).collect { "${it.declaringType.name}#${it.name}".toString() }.sort(),
            fields      : element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared()).collect {
                [name: it.name, type: type(it), modifiers: it.modifiers*.name().sort(), annotations: annotations(it)]
            }.sort { it.name },
            properties  : element.beanProperties.collect {
                [name: it.name, type: type(it), readOnly: it.readOnly, annotations: annotations(it),
                 read: it.readMethod.map { m -> m.name }.orElse(null), write: it.writeMethod.map { m -> m.name }.orElse(null),
                 field: it.field.isPresent()]
            }.sort { it.name },
            enumValues  : element instanceof EnumElement ? element.values() : null,
            nested      : element.getEnclosedElements(ElementQuery.of(ClassElement)).collect { it.name }.sort(),
        ]
    }

    private static Map method(MethodElement method) {
        [
            name       : method.name,
            returns    : [type: method.returnType.name, generic: method.genericReturnType.name, array: method.returnType.isArray()],
            params     : method.parameters.collect { parameter(it) },
            typeParams : method.declaredTypeVariables*.variableName,
            thrown     : method.thrownTypes*.name,
            modifiers  : method.modifiers*.name().sort(),
            annotations: annotations(method),
            declaring  : method.declaringType.name,
        ]
    }

    private static Map parameter(ParameterElement parameter) {
        [name: parameter.name, type: type(parameter), hasDefault: parameter.hasDefault(),
         nullable: parameter.isNullable(), annotations: annotations(parameter)]
    }

    private static Map type(TypedElement element) {
        [name: element.type.name, generic: element.genericType.name, array: element.type.isArray(),
         dims: element.type.arrayDimensions, args: element.genericType.typeArguments.collectEntries { k, v -> [k, v.name] }]
    }

    private static Map annotations(io.micronaut.inject.ast.Element element) {
        element.annotationNames.sort().collectEntries { name ->
            [name, element.getAnnotation(name)?.values?.findAll { k, v -> !visitorMutation(name, k.toString()) }
                ?.collectEntries { k, v -> [k.toString(), String.valueOf(v)] }]
        }
    }

    /**
     * Type-element visitors mutate the metadata of the classes being compiled and nothing
     * else, on every platform: `IntrospectedValidationIndexesVisitor` on the test classpath
     * adds `@Introspected(indexed = ...)` to a source class, and the same class read back
     * from TASTy or a class file has only what its author wrote. That is the one legitimate
     * difference between the two sides, so the comparison leaves it out.
     */
    private static boolean visitorMutation(String annotation, String member) {
        annotation == 'io.micronaut.core.annotation.Introspected' && member == 'indexed'
    }
}
