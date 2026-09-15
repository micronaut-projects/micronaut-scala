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

import io.micronaut.core.annotation.AnnotationMetadata
import io.micronaut.inject.BeanDefinition
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * The Scala counterpart of Core's {@code DeclaredAnnotationValuesByNameSpec} (Java and Kotlin):
 * {@code getDeclaredAnnotationValuesByName} answers a non-repeatable annotation as a
 * single-element list, exactly as {@code getAnnotationValuesByName} does.
 */
class ScalaDeclaredAnnotationValuesByNameSpec extends AbstractScalaTypeElementSpec {

    private static final String PKG = 'byname'
    private static final String FOO = PKG + '.Foo'
    private static final String Q = 'io.micronaut.scala.processing.fixtures.Location'
    private static final String MISSING = PKG + '.Missing'

    /**
     * Scala cannot declare a repeatable annotation -- {@code java.lang.annotation.Repeatable} takes a
     * Java annotation type -- so the repeatable rows use the {@code Location}/{@code Locations} fixtures
     * and only the non-repeatable annotation is declared in Scala.
     */
    private static String source(String annotations) {
        """
package $PKG

import io.micronaut.scala.processing.fixtures.Location
import io.micronaut.scala.processing.fixtures.Locations
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

class Foo(val value: String) extends StaticAnnotation

@Singleton
$annotations
class Bar
"""
    }

    void 'declared and inherited queries by name agree at compile time for #description'() {
        given:
        AnnotationMetadata metadata = buildClassElement("${PKG}.Bar", source(annotations)).getAnnotationMetadata()

        expect:
        probe(metadata, name) == [declared: expected, all: expected]

        where:
        description                        | annotations                                                       | name    || expected
        'a non-repeatable annotation'      | '@Foo("x")'                                                       | FOO     || ['x']
        'a repeatable written once'        | '@Location("a")'                                                  | Q       || ['a']
        'a repeatable written twice'       | '@Location("a") @Location("b")'                                   | Q       || ['a', 'b']
        'a container written by hand'      | '@Locations(Array(new Location("a"), new Location("b")))'         | Q       || ['a', 'b']
        'a missing annotation'             | '@Foo("x")'                                                       | MISSING || []
        'a repeatable that is not present' | '@Foo("x")'                                                       | Q       || []
    }

    void 'declared and inherited queries by name agree in the generated definition for #description'() {
        given:
        BeanDefinition<?> definition = buildBeanDefinition("${PKG}.Bar", source(annotations))
        AnnotationMetadata metadata = definition.getAnnotationMetadata()

        expect:
        probe(metadata, name) == [declared: expected, all: expected]

        where:
        description                        | annotations                                                       | name    || expected
        'a non-repeatable annotation'      | '@Foo("x")'                                                       | FOO     || ['x']
        'a repeatable written once'        | '@Location("a")'                                                  | Q       || ['a']
        'a repeatable written twice'       | '@Location("a") @Location("b")'                                   | Q       || ['a', 'b']
        'a container written by hand'      | '@Locations(Array(new Location("a"), new Location("b")))'         | Q       || ['a', 'b']
        'a missing annotation'             | '@Foo("x")'                                                       | MISSING || []
        'a repeatable that is not present' | '@Foo("x")'                                                       | Q       || []
    }

    void 'the declared annotation values by name carry the annotation name'() {
        given:
        AnnotationMetadata metadata = buildClassElement("${PKG}.Bar", source('@Foo("x")')).getAnnotationMetadata()

        expect:
        [
                declared: metadata.getDeclaredAnnotationValuesByName(FOO)*.getAnnotationName(),
                all     : metadata.getAnnotationValuesByName(FOO)*.getAnnotationName(),
        ] == [declared: [FOO], all: [FOO]]
    }

    private static Map<String, List<String>> probe(AnnotationMetadata metadata, String name) {
        [
                declared: metadata.getDeclaredAnnotationValuesByName(name).collect { it.stringValue().get() },
                all     : metadata.getAnnotationValuesByName(name).collect { it.stringValue().get() },
        ]
    }
}
