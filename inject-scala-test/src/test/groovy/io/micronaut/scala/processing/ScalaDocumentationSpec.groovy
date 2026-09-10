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
 * Parity for {@code Element.getDocumentation}, ported from the Java and Kotlin implementations
 * of the same SPI.
 *
 * <p>Documentation is the one input to compile-time generation that is not an annotation.
 * Configuration metadata takes every {@code description} from it, and micronaut-openapi and JSON
 * schema generation read the same SPI for their own descriptions, so a language that answers
 * {@code Optional.empty()} everywhere -- which Scala did -- produces structurally correct
 * artifacts that document nothing.</p>
 *
 * <p>The Scala interest is where the text lives. Two of these cases have no Java equivalent:
 * dotty hands back the comment with its delimiters still attached, where javac and KSP strip
 * them, and a class parameter is a property whose description can only be written as a
 * {@code @param} tag on the class -- the rule Java applies to records, reached here by the
 * ordinary way of declaring a configuration class.</p>
 */
class ScalaDocumentationSpec extends AbstractScalaTypeElementSpec {

    void "reads the documentation of a class, its methods and their parameters"() {
        given:
        def element = buildClassElement('docs.Greeter', '''
package docs

import jakarta.inject.Singleton

/** Greets a person.
  *
  * A second paragraph, which is still description.
  *
  * @param prefix the greeting to use
  */
@Singleton
class Greeter(val prefix: String):

  /** Greets someone.
    *
    * @param name the person to greet
    * @return the greeting
    */
  def greet(name: String): String = prefix + name
''')
        def greet = element.getEnclosedElements(ElementQuery.ALL_METHODS.named('greet'))[0]

        expect: '''unparsed documentation is the comment's content without its markers, which
                   is what javac and KSP hand back and what consumers parse tags out of'''
        !element.getDocumentation(false).get().contains('/**')
        !element.getDocumentation(false).get().contains('*/')
        element.getDocumentation(false).get().startsWith('Greets a person.')
        element.getDocumentation(false).get().contains('@param prefix')

        and: 'parsing keeps the whole description and drops the block tags'
        element.getDocumentation(true).get() == 'Greets a person.\n\nA second paragraph, which is still description.'
        greet.getDocumentation(true).get() == 'Greets someone.'

        and: 'a parameter is documented by its method\'s tag, and only when parsing is allowed'
        greet.parameters[0].getDocumentation(true).get() == 'the person to greet'
        greet.parameters[0].getDocumentation(false).isEmpty()
    }

    void "documents a class parameter from the tag on the class"() {
        given: '''the ordinary way to declare a Scala property: a class parameter. There is
                  nowhere else the author could write its description -- the field is generated
                  and so are the accessors -- so the class tag is the only source'''
        def element = buildClassElement('docs.Engine', '''
package docs

/** An engine.
  *
  * @param manufacturer who made it
  */
class Engine(val manufacturer: String):

  /** How many cylinders it has. */
  var cylinders: Int = 4
''')
        def properties = element.getBeanProperties().collectEntries {
            [it.name, it.getDocumentation(true).orElse(null)]
        }

        expect: 'the class parameter is documented by the class'
        properties['manufacturer'] == 'who made it'

        and: 'and a member declared in the body is documented where it is declared'
        properties['cylinders'] == 'How many cylinders it has.'
    }

    void "reports nothing for an undocumented element rather than something empty"() {
        given:
        def element = buildClassElement('docs.Undocumented', '''
package docs

class Undocumented(val value: String):
  def act(): String = value
''')

        expect:
        element.getDocumentation(true).isEmpty()
        element.getDocumentation(false).isEmpty()
        element.getEnclosedElements(ElementQuery.ALL_METHODS.named('act'))[0].getDocumentation(true).isEmpty()
    }

    void "strips the leading asterisk however the author aligned it"() {
        given: '''Scala's own style puts the asterisk under the second character of the opening
                  delimiter, where Java puts it under the first. Both are written in the wild and
                  neither is the compiler's business, so the text has to survive both'''
        def element = buildClassElement('docs.Aligned', '''
package docs

/** Scala alignment.
  *
  * Continued here.
  */
class Aligned

/**
 * Java alignment.
 *
 * Continued here.
 */
class JavaAligned
''')
        def javaAligned = buildClassElement('docs.JavaAligned', '''
package docs

/**
 * Java alignment.
 *
 * Continued here.
 */
class JavaAligned
''')

        expect:
        element.getDocumentation(true).get() == 'Scala alignment.\n\nContinued here.'
        javaAligned.getDocumentation(true).get() == 'Java alignment.\n\nContinued here.'
    }

    void "carries the documentation into generated configuration metadata"() {
        when: '''the generated file is what every downstream reader consumes -- the IDE
                 completion, and the descriptions in generated documentation'''
        def classLoader = buildClassLoader('docs.EngineConfig', '''
package docs

import io.micronaut.context.annotation.ConfigurationProperties

/** Configures the engine.
  *
  * @param manufacturer the company that made the engine
  * @param cylinders how many cylinders it has
  */
@ConfigurationProperties("engine")
case class EngineConfig(manufacturer: String, cylinders: Int)
''')
        // Every jar on the classpath ships one of these, so the generated copy is the one
        // written into this compilation's own output directory.
        def generated = classLoader.getResources('META-INF/spring-configuration-metadata.json')
            .toList().find { it.toString().contains('micronaut-scala-test') }

        then:
        generated != null

        and: 'the group takes its description from the class'
        generated.text.contains('"description":"Configures the engine."')

        and: 'and each property from the tag documenting it'
        generated.text.contains('"description":"the company that made the engine"')
        generated.text.contains('"description":"how many cylinders it has"')
    }
}
