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

import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.EnumElement
import io.micronaut.inject.ast.FieldElement
import io.micronaut.inject.ast.MethodElement
import io.micronaut.scala.processing.fixtures.ExternalLevel
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * What the plugin reads from a Java class file itself, where the compiler's reading of the
 * class stops short.
 *
 * <p>dotty's {@code ClassfileParser} gives the plugin a Java class's public and protected
 * members with their signatures and their member annotations, and nothing else: no parameter
 * annotations, no type annotations, no private members, no parameter names without
 * {@code -parameters}. Micronaut needs all of it -- {@code @Nullable} on a parameter decides
 * whether an argument may be absent, {@code @Inject} on a private field is an injection
 * point -- so those parts come from the class file through the JDK's class-file API, and this
 * spec covers each of them against fixtures compiled by javac in this build.</p>
 */
class ScalaJavaClassFileSpec extends AbstractScalaTypeElementSpec {

    private static final String TAGGED = 'io.micronaut.scala.processing.fixtures.ExternalTagged'
    private static final String NOTE = 'io.micronaut.scala.processing.fixtures.ExternalNote'

    private static Map<String, ClassElement> fixtures

    private ClassElement fixture(String name) {
        if (fixtures == null) {
            ClassElement uses = buildClassElement('shelf.Uses', '''
package shelf

import io.micronaut.scala.processing.fixtures.*

class Uses:
  def shapes: ExternalJavaShapes = null
  def inner: ExternalJavaShapes#Inner = null
  def nested: ExternalJavaShapes.Nested = null
  def level: ExternalLevel = null
  def point: ExternalPoint = null
''')
            fixtures = ['shapes', 'inner', 'nested', 'level', 'point'].collectEntries { probe ->
                [(probe): method(uses, probe).returnType]
            }
        }
        fixtures[name]
    }

    void "a parameter annotation carries a value of every kind the class file can hold"() {
        given:
        def parameters = fixture('shapes').primaryConstructor.get().parameters

        expect: 'the member each parameter sets'
        tagged(parameters[0]).enumValue('level', ExternalLevel).get() == ExternalLevel.HIGH
        tagged(parameters[1]).classValue('type').get() == List
        tagged(parameters[2]).stringValues('names') == ['a', 'b'] as String[]
        tagged(parameters[3]).getAnnotation('note').get().stringValue().get() == 'inner'
        tagged(parameters[3]).getAnnotation('note').get().annotationName == NOTE
        (0..3).collect { tagged(parameters[it]).intValue('weight').get() } == [1, 2, 3, 4]

        and: '''the members it leaves to the annotation type are not values of the annotation,
                 as they are not in javac's model either; their defaults come from the class
                 file's `AnnotationDefault` attributes, of every kind again'''
        !tagged(parameters[0]).contains('type')
        !tagged(parameters[1]).contains('level')
        def defaults = tagged(parameters[1]).defaultValues
        defaults.keySet()*.toString().sort() == ['ceiling', 'level', 'names', 'note', 'type']
        defaults['level'] == 'LOW'
        defaults['type'] instanceof AnnotationClassValue
        defaults['type'].name == 'java.lang.Object'
        (defaults['names'] as List).isEmpty()
        defaults['note'].annotationName == NOTE
        defaults['note'].values.isEmpty()
        defaults['ceiling'] == Long.MAX_VALUE

        and: 'a parameter without annotations has none'
        parameters[4].annotationNames.isEmpty()
    }

    void "a type annotation on a return type or a field is the member's annotation"() {
        given:
        ClassElement shapes = fixture('shapes')

        expect: '`@Nullable` on a return type, which is where jspecify-style nullability lives'
        method(shapes, 'first').isNullable()
        method(shapes, 'first').returnType.name == 'java.lang.String'
        !method(shapes, 'wrap').isNullable()

        and: 'on a field'
        field(shapes, 'label').isNullable()
        !field(shapes, 'tagged').isNullable()

        and: 'an annotation on a type-variable parameter, which has no class in its erasure'
        tagged(method(shapes, 'wrap').parameters[0]).intValue('weight').get() == 5
        method(shapes, 'wrap').parameters[0].genericType.isTypeVariable()
        method(shapes, 'wrap').declaredTypeVariables*.variableName == ['T']
    }

    void "a constant field has its value"() {
        given:
        ClassElement shapes = fixture('shapes')

        expect:
        field(shapes, 'GREETING').constantValue == 'hello'
        field(shapes, 'LIMIT').constantValue == 7
        field(shapes, 'GREETING').isStatic()
        field(shapes, 'GREETING').isFinal()
        field(shapes, 'label').constantValue == null
    }

    void "an inner class's constructor has the parameters written, with their annotations"() {
        given: '''the class file's constructor takes the enclosing instance first, and its
                  parameter annotations count from the first written parameter, so the
                  alignment has to allow for the parameter the compiler does not show'''
        ClassElement inner = fixture('inner')
        ClassElement nested = fixture('nested')

        expect:
        inner.isInner()
        inner.name == 'io.micronaut.scala.processing.fixtures.ExternalJavaShapes$Inner'
        def constructor = inner.primaryConstructor.get()
        constructor.parameters*.type*.name == ['int', 'java.lang.String']
        tagged(constructor.parameters[0]).intValue('weight').get() == 8
        constructor.parameters[1].isNullable()
        !constructor.parameters[0].isNullable()

        and: '''a static nested class is nested too -- `isInner` is javac's `isNested` -- and
                 both are nested types of the outer class'''
        nested.isInner()
        method(nested, 'greeting').returnType.name == 'java.lang.String'
        fixture('shapes').getEnclosedElements(ElementQuery.of(ClassElement))*.name.sort() == [
            'io.micronaut.scala.processing.fixtures.ExternalJavaShapes$Inner',
            'io.micronaut.scala.processing.fixtures.ExternalJavaShapes$Nested',
        ]
    }

    void "a Java enum has its constants, its own members and its constructor"() {
        given:
        ClassElement level = fixture('level')

        expect:
        level.isEnum()
        level instanceof EnumElement
        (level as EnumElement).values() == ['LOW', 'HIGH']

        and: 'the constants are static final fields of the enum type'
        def constants = level.getEnclosedElements(ElementQuery.ALL_FIELDS.includeEnumConstants())
        constants*.name.sort() == ['HIGH', 'LOW', 'rank']
        def high = constants.find { it.name == 'HIGH' }
        high.isStatic()
        high.type.name == level.name

        and: 'its own field and method are members like any other'
        field(level, 'rank').isPrivate()
        field(level, 'rank').type.name == 'int'
        method(level, 'rank').returnType.name == 'int'
        method(level, 'rank').isPublic()

        and: 'javac\'s values and valueOf are there, and the constructor takes the declared parameter'
        method(level, 'values').isStatic()
        method(level, 'valueOf').parameters*.type*.name == ['java.lang.String']
        level.getEnclosedElements(ElementQuery.CONSTRUCTORS).size() == 1
        level.getEnclosedElements(ElementQuery.CONSTRUCTORS)[0].parameters*.type*.name == ['int']
    }

    private static AnnotationValue<?> tagged(io.micronaut.inject.ast.Element element) {
        def annotation = element.getAnnotation(TAGGED)
        assert annotation != null: "${element} has no @ExternalTagged, only ${element.annotationNames}"
        annotation
    }

    private static MethodElement method(ClassElement element, String name) {
        def methods = element.getEnclosedElements(ElementQuery.ALL_METHODS.named(name))
        assert !methods.isEmpty(): "${element.name} has no method ${name}"
        methods[0]
    }

    private static FieldElement field(ClassElement element, String name) {
        def fields = element.getEnclosedElements(ElementQuery.ALL_FIELDS.includeEnumConstants().named(name))
        assert !fields.isEmpty(): "${element.name} has no field ${name}"
        fields[0]
    }
}
