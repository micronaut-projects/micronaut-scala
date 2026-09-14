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

import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Ported from the Java module's {@code ClassElementSpec} -- "fields selection", "first inner
 * class not breaking method's owning and declaring class", "visit methods that take and
 * return enums", "visit methods that take and return arrays", and "bean properties defined
 * with accessors style none".
 *
 * <p>These all answer correctly already. The one place Scala differs is field visibility:
 * every {@code val} and {@code var} is a private field behind accessors, so the
 * modifier-based selections that pick out public fields in Java select nothing here.</p>
 */
class ScalaElementSelectionParitySpec extends AbstractScalaTypeElementSpec {

    private static final String PET = '''
package test

class Pet {
  var pub: Int = 0
  private var prvn: String = ""
  protected var protectme: String = ""
  private[test] var packprivme: String = ""
}
'''

    void 'a nested class does not change a method owning or declaring type'() {
        given: 'the Java case is an inner class declared before the method'
        def element = buildClassElement('test.TestController', '''
package test

import io.micronaut.http.annotation.*

@Controller("/test")
class TestController {
  class SomeException extends RuntimeException
  @Get
  def hello(): String = "HW"
}
''')

        when:
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared()).first()

        then:
        method.owningType.getName() == 'test.TestController'
        method.declaringType.getName() == 'test.TestController'
    }

    void 'fields can be selected by name and by type'() {
        given:
        def element = buildClassElement('test.Pet', PET)

        expect:
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.named { it == 'pub' })*.name == ['pub']

        and:
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.typed { it.getName() == 'java.lang.String' })*.name.sort() ==
                ['packprivme', 'protectme', 'prvn']
    }

    void 'no field selection by public modifier finds anything'() {
        given:
        def element = buildClassElement('test.Pet', PET)

        expect: 'Java finds its public fields here; every Scala field is private'
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.modifiers {
            it.contains(ElementModifier.PUBLIC) && it.size() == 1
        }).isEmpty()
        element.getEnclosedElements(ElementQuery.ALL_FIELDS.filter { it.isPublic() }).isEmpty()

        and: 'which is a statement about visibility, not about the fields being missing'
        element.getEnclosedElements(ElementQuery.ALL_FIELDS)*.name.sort() ==
                ['packprivme', 'protectme', 'prvn', 'pub']
    }

    void 'an enum and an array of it survive as method types'() {
        given:
        def element = buildClassElement('test.Test', '''
package test

enum Colour:
  case Red, Blue

class Test {
  def colour(c: Colour): Colour = c
  def colours(c: Array[Colour]): Array[Colour] = c
}
''')
        def methods = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [it.name, it] }

        expect:
        methods.colour.getReturnType().isEnum()
        methods.colour.getParameters()[0].getType().isEnum()

        and:
        methods.colours.getReturnType().isEnum()
        methods.colours.getReturnType().getArrayDimensions() == 1
        methods.colours.getParameters()[0].getType().getArrayDimensions() == 1
    }

    void 'AccessorsStyle with no prefix finds Scala-shaped accessors'() {
        given:
        def element = buildClassElement('test.Test', '''
package test

import io.micronaut.core.annotation.AccessorsStyle

@AccessorsStyle(readPrefixes = Array(""), writePrefixes = Array(""))
class Test {
  private var nm: String = ""
  def name(): String = nm
  def name(value: String): Unit = { nm = value }
}
''')

        expect:
        element.getBeanProperties()*.name == ['name']
        element.getBeanProperties().first().getType().getName() == 'java.lang.String'
    }

    void 'Introspected excludes removes a property'() {
        given:
        def element = buildClassElement('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(excludes = Array("secret"))
class Test(val visible: String, val secret: String)
''')

        expect:
        element.getBeanProperties()*.name == ['visible']
    }
}
