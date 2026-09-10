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
 * Annotations written on a class parameter, which is where Scala declares a property.
 *
 * <p>A class parameter is one declaration that becomes a private field, a pair of accessors and
 * a property, and Scala applies an annotation exactly where it is written unless the annotation
 * opts into {@code scala.annotation.meta} targets. Java has the same shape in a record and
 * resolves it in the compiler, propagating a record component's annotations to the field and the
 * accessor, so anything reading the property finds them.</p>
 *
 * <p>Without the same step the annotation was reachable only from the constructor parameter,
 * which is not where anything looks: {@code @Id} on a {@code @MappedEntity} case class was
 * invisible and Micronaut Data rejected the entity for having no identity.</p>
 */
class ScalaClassParameterAnnotationSpec extends AbstractScalaTypeElementSpec {

    void "carries a case class parameter's annotations onto its property"() {
        given:
        def element = buildClassElement('params.Book', '''
package params

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity
case class Book(@Id @GeneratedValue id: java.lang.Long, title: String)
''')
        def properties = element.getBeanProperties().collectEntries {
            [it.name, it.annotationMetadata.annotationNames as Set]
        }

        expect:
        properties['id'].contains('io.micronaut.data.annotation.Id')
        properties['id'].contains('io.micronaut.data.annotation.GeneratedValue')

        and: 'and a parameter with no annotations gains none'
        !properties['title'].contains('io.micronaut.data.annotation.Id')
    }

    void "carries them onto an ordinary class parameter too, not only a case class"() {
        given:
        def element = buildClassElement('params.Plain', '''
package params

import io.micronaut.data.annotation.Id

class Plain(@Id val code: String)
''')

        expect:
        element.getBeanProperties()
            .find { it.name == 'code' }
            .annotationMetadata
            .hasAnnotation('io.micronaut.data.annotation.Id')
    }

    void "leaves the constructor parameter itself carrying them"() {
        given: 'the parameter is still where a constructor-injection consumer reads them'
        def element = buildClassElement('params.Book', '''
package params

import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

@MappedEntity
case class Book(@Id id: java.lang.Long, title: String)
''')

        expect:
        element.primaryConstructor.get().parameters[0]
            .annotationMetadata.hasAnnotation('io.micronaut.data.annotation.Id')
    }
}
