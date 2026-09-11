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

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code ClassElementAnnotationsRetaining}.
 *
 * <p>A property is assembled from several members, and an annotation written at any of them has
 * to be visible through the property. Java's spec puts the same annotation on the field, the
 * getter and the setter in turn and asks the property each time; the answer has to be the same,
 * because which member an author chooses is a matter of style.</p>
 *
 * <p>In Scala it is not even a choice in the same way. A {@code var} is one declaration that
 * becomes three symbols, and a hand-written accessor pair is two declarations with no field at
 * all -- so "where the annotation was written" ranges over shapes Java cannot express. This is
 * also the reading end of the type-argument annotation fix on this branch: the annotation now
 * has to arrive on the argument, and arrive there whichever member carried it.</p>
 */
class ScalaPropertyAnnotationRetentionSpec extends AbstractScalaTypeElementSpec {

    private static final String VALID = 'jakarta.validation.Valid'

    private static String source(String body) {
        '''
package retention

import io.micronaut.core.annotation.Introspected
import jakarta.validation.Valid

@Introspected
class Ingredient(val name: String)

@Introspected
class Salad:
''' + body
    }

    void "sees a type-argument annotation written on a var"() {
        when: 'one declaration, which becomes a field and two accessors'
        def element = buildClassElement('retention.Salad', source('''
  var ingredients: java.util.List[Ingredient @Valid] = null
'''))
        def property = element.beanProperties.find { it.name == 'ingredients' }

        then:
        property != null
        property.type.typeArguments['E'].annotationMetadata.hasStereotype(VALID)
    }

    void "sees a type-argument annotation written on the reader of an accessor pair"() {
        when: 'two declarations and no field, with the annotation on the reader only'
        def element = buildClassElement('retention.Salad', source('''
  private var backing: java.util.List[Ingredient] = null

  def ingredients: java.util.List[Ingredient @Valid] = backing
  def ingredients_=(value: java.util.List[Ingredient]): Unit = backing = value
'''))
        def property = element.beanProperties.find { it.name == 'ingredients' }

        then: 'the pair is a property at all only because of the accessor-pair support'
        property != null
        property.type.typeArguments['E'].annotationMetadata.hasStereotype(VALID)
    }

    void "sees an annotation written on a read-only reader"() {
        when: 'a val, so there is no setter to carry anything'
        def element = buildClassElement('retention.Salad', source('''
  val ingredients: java.util.List[Ingredient @Valid] = null
'''))
        def property = element.beanProperties.find { it.name == 'ingredients' }

        then:
        property != null
        property.isReadOnly()
        property.type.typeArguments['E'].annotationMetadata.hasStereotype(VALID)
    }

    void "sees a plain annotation written on a var"() {
        when: 'not a type use this time -- the annotation is on the member itself'
        def element = buildClassElement('retention.Salad', source('''
  @Valid
  var ingredients: java.util.List[Ingredient] = null
'''))
        def property = element.beanProperties.find { it.name == 'ingredients' }

        then:
        property != null
        property.annotationMetadata.hasStereotype(VALID)
    }
}
