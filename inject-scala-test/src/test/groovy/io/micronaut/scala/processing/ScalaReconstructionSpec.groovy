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
import io.micronaut.inject.ast.EnumElement
import io.micronaut.inject.ast.WildcardElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ScalaReconstructionSpec extends AbstractScalaTypeElementSpec {

    void "reconstructs Scala fields methods parameters and return types"() {
        when:
        def element = buildClassElement('reconstruct.Vehicle', '''
package reconstruct

class Vehicle(val name: String, var started: Boolean):
  val engine: Engine = Engine("v8")
  private val serial: String = "hidden"

  def start(speed: Int, mode: String): java.util.Map[String, java.lang.Integer] =
    java.util.Map.of(mode, speed)

case class Engine(name: String)
''')
        def fields = element.getEnclosedElements(ElementQuery.ALL_FIELDS).collectEntries { [(it.name): it] }
        def properties = element.beanProperties.collectEntries { [(it.name): it] }
        def start = element.getEnclosedElement(ElementQuery.ALL_METHODS.named({ it == 'start' })).get()

        then:
        fields.keySet().containsAll(['name', 'started', 'engine', 'serial'])
        fields.engine.type.name == 'reconstruct.Engine'
        fields.serial.private
        properties.keySet() == ['name', 'started', 'engine'] as Set
        properties.name.type.name == String.name
        properties.started.type.name == Boolean.TYPE.name
        start.parameters*.name == ['speed', 'mode']
        start.parameters[0].type.name == Integer.TYPE.name
        start.parameters[1].type.name == String.name
        start.returnType.name == Map.name
        start.returnType.typeArguments.K.name == String.name
        start.returnType.typeArguments.V.name == Integer.name
    }

    void "reconstructs Scala arrays wildcards and type variables"() {
        when:
        def element = buildClassElement('reconstruct.Holder', '''
package reconstruct

class Holder[T <: Number](
  val matrix: Array[Array[Int]],
  val values: java.util.List[_ <: T]
):
  def first[S <: CharSequence](value: S): S = value
''')
        def properties = element.beanProperties.collectEntries { [(it.name): it] }
        def matrix = properties.matrix.type
        def wildcard = properties.values.type.typeArguments.E as WildcardElement
        def classPlaceholder = element.declaredGenericPlaceholders.first()
        def method = element.findMethod('first').get()
        def methodPlaceholder = method.declaredTypeVariables.first()

        then:
        matrix.array
        matrix.arrayDimensions == 2
        classPlaceholder.variableName == 'T'
        classPlaceholder.name == Number.name
        wildcard.wildcard
        wildcard.bounded
        wildcard.upperBounds.first().genericPlaceholder
        wildcard.upperBounds.first().variableName == 'T'
        methodPlaceholder.variableName == 'S'
        methodPlaceholder.name == CharSequence.name
        method.returnType.genericPlaceholder
        method.returnType.variableName == 'S'
        method.parameters[0].type.genericPlaceholder
        method.parameters[0].type.variableName == 'S'
    }

    void "reconstructs inherited type arguments through Scala traits"() {
        when:
        def element = buildClassElement('reconstruct.BookRepository', '''
package reconstruct

trait Repository[E, ID]:
  def save(entity: E): ID

trait CrudRepository[E, ID] extends Repository[E, ID]

class Book(val title: String)

class BookRepository extends CrudRepository[Book, java.lang.Long]:
  override def save(entity: Book): java.lang.Long = 1L
''')
        def save = element.findMethod('save').get()

        then:
        element.interfaces*.name == ['reconstruct.CrudRepository']
        element.isAssignable('reconstruct.Repository')
        save.parameters[0].type.name == 'reconstruct.Book'
        save.returnType.name == Long.name
    }

    void "reconstructs Scala enums and nested classes"() {
        when:
        def enumElement = buildClassElement('reconstruct.Color', '''
package reconstruct

enum Color:
  case Red, Blue
''') as EnumElement
        def outer = buildClassElement('reconstruct.Outer', '''
package reconstruct

class Outer:
  class Inner

object Outer:
  class Nested
''')
        def enclosed = outer.getEnclosedElements(ElementQuery.of(io.micronaut.inject.ast.ClassElement))

        then:
        enumElement.enum
        enumElement.values() == ['Red', 'Blue']
        enumElement.elements()*.type*.name == ['reconstruct.Color', 'reconstruct.Color']
        enclosed*.name as Set == ['reconstruct.Outer$Inner', 'reconstruct.Outer$Nested'] as Set
        enclosed.every { it.inner }
        enclosed.every { it.enclosingType.get().name == 'reconstruct.Outer' }
    }
}
