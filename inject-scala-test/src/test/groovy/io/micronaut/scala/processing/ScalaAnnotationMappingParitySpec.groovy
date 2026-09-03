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
import io.micronaut.scala.processing.test.annotation.ScalaMappedResult
import io.micronaut.scala.processing.test.annotation.ScalaMappedSource
import io.micronaut.scala.processing.test.annotation.ScalaTransformedSource
import io.micronaut.scala.processing.test.annotation.remap.ScalaRemappedSource

class ScalaAnnotationMappingParitySpec extends AbstractScalaTypeElementSpec {

    void "applies Scala annotation mappers transformers and remappers"() {
        when:
        def mapped = buildClassElement('annmapping.MappedEngine', '''
package annmapping

import io.micronaut.scala.processing.test.annotation.ScalaMappedSource

@ScalaMappedSource(property = "configured", count = 7)
class MappedEngine
''')
        def transformed = buildClassElement('annmapping.TransformedEngine', '''
package annmapping

import io.micronaut.scala.processing.test.annotation.ScalaTransformedSource

@ScalaTransformedSource
class TransformedEngine
''')
        def remapped = buildClassElement('annmapping.RemappedEngine', '''
package annmapping

import io.micronaut.scala.processing.test.annotation.remap.ScalaRemappedSource

@ScalaRemappedSource
class RemappedEngine
''')

        then:
        mapped.hasAnnotation(ScalaMappedSource)
        mapped.getAnnotation(ScalaMappedResult).stringValue().get() == 'mapper-configured-7'

        !transformed.hasAnnotation(ScalaTransformedSource)
        transformed.getAnnotation(ScalaMappedResult).stringValue().get() == 'transformer'

        !remapped.hasAnnotation(ScalaRemappedSource)
        remapped.getAnnotation(ScalaMappedResult).stringValue().get() == 'remapper'
    }
}
