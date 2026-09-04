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

import io.micronaut.context.annotation.Requires
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotatingVisitor
import io.micronaut.scala.processing.test.ScalaElementMutationVisitor
import jakarta.inject.Singleton

import java.util.function.Supplier

class ScalaElementMutationParitySpec extends AbstractScalaTypeElementSpec {

    void "visitor mutations cover Scala class method field property parameter return and type arguments"() {
        when:
        def element = ScalaElementMutationVisitor.withMutations({
            buildClassElement('mutation.TestBean', '''
package mutation

class TestBean(var name: String, val values: java.util.List[String]):
  val id: Identifier = Identifier("one")

  def convert(input: java.util.List[String]): java.util.Map[String, java.lang.Integer] =
    java.util.Map.of("value", 1)

case class Identifier(value: String)
''')
        } as Supplier)
        def fields = element.getEnclosedElements(ElementQuery.ALL_FIELDS).collectEntries { [(it.name): it] }
        def properties = element.beanProperties.collectEntries { [(it.name): it] }
        def convert = element.findMethod('convert').get()
        def input = convert.parameters[0]

        then:
        element.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'class'
        fields.id.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'field'
        fields.id.type.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'field-type'
        properties.name.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'property'
        convert.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'method'
        input.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'parameter'
    }

    void "visitor mutations preserve Scala return and parameter type annotations"() {
        when:
        def element = ScalaElementMutationVisitor.withMutations({
            buildClassElement('mutation.TypeBean', '''
package mutation

class TypeBean:
  def convert(input: String): java.util.Optional[String] =
    java.util.Optional.of(input)
''')
        } as Supplier)
        def convert = element.findMethod('convert').get()
        def input = convert.parameters[0]

        then:
        convert.returnType.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'return-type'
        input.type.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'parameter-type'
    }

    void "visitor mutations preserve Scala type argument annotations"() {
        when:
        def element = ScalaElementMutationVisitor.withMutations({
            buildClassElement('mutation.TypeArgumentBean', '''
package mutation

class TypeArgumentBean(val values: java.util.List[String]):
  def convert(input: java.util.List[String]): java.util.Map[String, java.lang.Integer] =
    java.util.Map.of("value", 1)
''')
        } as Supplier)
        def fields = element.getEnclosedElements(ElementQuery.ALL_FIELDS).collectEntries { [(it.name): it] }
        def convert = element.findMethod('convert').get()
        def input = convert.parameters[0]

        then:
        fields.values.type.typeArguments.E.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'type-argument'
        convert.returnType.typeArguments.K.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'type-argument'
        convert.returnType.typeArguments.V.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'type-argument'
        input.type.typeArguments.E.stringValue(ScalaElementMutationVisitor.ANN, 'target').get() == 'type-argument'
    }

    void "visitor mutations preserve repeatables empty arrays and source-defined stereotypes"() {
        when:
        def element = ScalaElementMutationVisitor.withMutations({
            buildClassElement('mutation.TestBean', '''
package mutation

class TestBean:
  def run(): String = "ok"
''')
        } as Supplier)
        def requires = element.getAnnotationValuesByType(Requires)
        def run = element.findMethod('run').get()

        then:
        requires.size() == 2
        requires.find { it.stringValues('env').toList() == ['test'] } != null
        requires.find { it.stringValue('property').orElse(null) == 'feature.enabled' } != null
        run.stringValues(ScalaElementMutationVisitor.ANN, 'values').length == 0

        when:
        def stereotyped = ScalaAnnotatingVisitor.withClassAnnotation('mutation.MySingleton', {
            buildClassElement('mutation.Engine', '''
package mutation

import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

@Singleton
class MySingleton extends StaticAnnotation

@MySingleton
class Registered

class Engine
''')
        } as Supplier)

        then:
        stereotyped.hasAnnotation('mutation.MySingleton')
        stereotyped.hasStereotype(Singleton)
    }
}
