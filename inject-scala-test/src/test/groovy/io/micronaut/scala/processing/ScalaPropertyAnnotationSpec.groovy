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
import io.micronaut.inject.ast.PropertyElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import jakarta.inject.Named

/**
 * Scala targets an annotation at the getter or the backing field independently, so a
 * property's annotations are the union of both. Taking the getter's whenever it had any
 * meant a field-targeted annotation was silently discarded from any property that also
 * carried a getter-targeted one.
 */
class ScalaPropertyAnnotationSpec extends AbstractScalaTypeElementSpec {

    void 'a property keeps annotations targeted at both the getter and the field'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Named
import scala.annotation.meta.field
import scala.annotation.meta.getter

@Introspected
class Target {
  @(Deprecated @getter)
  @(Named @field)("fieldName")
  var foo: String = ""
}
''')

        when:
        def property = element.getEnclosedElements(ElementQuery.of(PropertyElement)).find { it.getName() == 'foo' }

        then:
        property != null
        property.hasAnnotation(Deprecated)
        property.hasAnnotation(Named)
        property.stringValue(Named).get() == 'fieldName'
    }

    void 'a field-only annotation is still seen when the getter has none'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Named
import scala.annotation.meta.field

@Introspected
class Target {
  @(Named @field)("fieldName")
  var foo: String = ""
}
''')

        when:
        def property = element.getEnclosedElements(ElementQuery.of(PropertyElement)).find { it.getName() == 'foo' }

        then:
        property != null
        property.hasAnnotation(Named)
    }
}
