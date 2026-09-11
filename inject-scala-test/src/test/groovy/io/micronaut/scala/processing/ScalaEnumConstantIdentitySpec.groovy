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
 * Every other element kind is handed out through a per-class cache, so a visitor that
 * annotates an element through {@code ElementQuery} and something that reads it back
 * later see one element. {@code EnumElement.elements()} constructed a fresh element each
 * call instead, so an annotation added through a query was invisible through the
 * enum-specific accessor -- and each call to it produced a different element again.
 */
class ScalaEnumConstantIdentitySpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package probe

enum Color:
  case Red, Blue
'''

    void 'an annotation added through a query is visible through elements()'() {
        given:
        def element = buildClassElement('probe.Color', SOURCE)
        def queried = element.getEnclosedElements(ElementQuery.ALL_FIELDS.includeEnumConstants())
                .find { it.name == 'Red' }

        when: 'this is what a visitor does'
        queried.annotate('jakarta.inject.Named')

        then:
        element.elements().find { it.name == 'Red' }.hasAnnotation('jakarta.inject.Named')
    }

    void 'elements() hands out the same element every time'() {
        given:
        def element = buildClassElement('probe.Color', SOURCE)

        expect:
        element.elements().find { it.name == 'Red' }
                .is(element.elements().find { it.name == 'Red' })
    }

    void 'the constants are still all there'() {
        given:
        def element = buildClassElement('probe.Color', SOURCE)

        expect:
        element.elements()*.name == ['Red', 'Blue']
        element.values() == ['Red', 'Blue']
    }
}
