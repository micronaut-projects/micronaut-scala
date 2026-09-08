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
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * Micronaut matches bean types, {@code @Requires} conditions and executable handlers
 * through {@code isAssignable}. A {@code ClassElement} names its component type and
 * reports array dimensions separately, so an element for {@code Array[String]} is named
 * {@code java.lang.String} -- and comparing names alone made every array assignable to
 * the type it is an array of, and to any array of any rank.
 */
class ScalaAssignabilitySpec extends AbstractScalaTypeElementSpec {

    private Map types() {
        def element = buildClassElement('probe.Holder', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Holder {
  def name(): String = null
  def names(): Array[String] = null
  def sequences(): Array[CharSequence] = null
  def tables(): Array[Array[String]] = null
}
''')
        def methods = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
        methods.collectEntries { [it.name, it.getReturnType()] }
    }

    void 'an array is not assignable to its component type'() {
        given:
        def types = types()

        expect:
        !types.names.isAssignable('java.lang.String')
        !types.names.isAssignable(types.name)

        and: 'nor the other way round'
        !types.name.isAssignable(types.names)
    }

    void 'arrays of different rank are not assignable to each other'() {
        given:
        def types = types()

        expect:
        !types.tables.isAssignable(types.names)
        !types.names.isAssignable(types.tables)
    }

    void 'an array is still assignable to itself and to the types every array is'() {
        given:
        def types = types()

        expect: 'the fix must not make arrays assignable to nothing'
        types.names.isAssignable(types.names)
        types.names.isAssignable('java.lang.Object')
        types.names.isAssignable('java.io.Serializable')
        types.names.isAssignable('java.lang.Cloneable')
    }

    void 'array assignment stays covariant in the component type'() {
        given:
        def types = types()

        expect: 'String[] is a CharSequence[], as in Java'
        types.names.isAssignable(types.sequences)
        !types.sequences.isAssignable(types.names)
    }

    void 'a classpath array is not assignable to its component type'() {
        when:
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            def string = context.getClassElement('java.lang.String').orElse(null)
            def array = string.withArrayDimensions(1)
            found.toComponent = array.isAssignable('java.lang.String')
            found.toComponentElement = array.isAssignable(string)
            found.toItself = array.isAssignable(array)
            found.toObject = array.isAssignable('java.lang.Object')
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })

        then:
        !found.toComponent
        !found.toComponentElement

        and:
        found.toItself
        found.toObject
    }
}
