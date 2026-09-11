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
 * {@code ALL_FIELDS} never walked the type hierarchy at all, so an inherited field was
 * invisible however it was declared -- and inherited {@code @Inject}/{@code @Value} fields
 * and inherited {@code @ConfigurationProperties} state were therefore always missed.
 */
class ScalaInheritedFieldSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import jakarta.inject.Singleton

class Base {
  protected var baseField: String = "base"
}

@Singleton
class Child extends Base {
  protected var childField: Int = 1
}
'''

    void 'inherited fields are visible through ALL_FIELDS'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_FIELDS)*.name

        then:
        names.contains('childField')
        names.contains('baseField')
    }

    void 'onlyDeclared still reports only the declared fields'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())*.name

        then:
        names.contains('childField')
        !names.contains('baseField')
    }

    void 'an inherited field is reported once'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when: 'the hierarchy is walked, which it previously was not'
        def baseFields = element.getEnclosedElements(ElementQuery.ALL_FIELDS).findAll { it.name == 'baseField' }

        then: 'walking a diamond of traits must not report the same field twice'
        baseFields.size() == 1
        baseFields[0].declaringType.name == 'test.Base'
    }

    void 'an inherited field type is resolved against the parameterisation'() {
        given:
        def element = buildClassElement('test.Concrete', '''
package test

import jakarta.inject.Singleton

class Holder[T] {
  protected var held: T = null.asInstanceOf[T]
}

@Singleton
class Concrete extends Holder[String]
''')

        when:
        def held = element.getEnclosedElements(ElementQuery.ALL_FIELDS).find { it.name == 'held' }

        then: '''the generic type is what the field holds here; the type stays the declared
                 one, which is what the field compiles to. This is the split javac's model
                 makes -- JavaFieldElement.getType() resolves with no generics and
                 getGenericType() with the declaring type's arguments -- and the one the
                 writers rely on: the field's descriptor from the first, the injection
                 point's Argument from the second'''
        held != null
        held.genericType.name == 'java.lang.String'
        held.type.name == 'java.lang.Object'
    }
}
