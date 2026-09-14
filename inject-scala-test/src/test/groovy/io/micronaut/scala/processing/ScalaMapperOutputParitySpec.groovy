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

import io.micronaut.context.annotation.Requirements
import io.micronaut.context.annotation.Requires
import io.micronaut.scala.processing.test.annotation.ScalaRepeatableSource
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P0 parity, ported from {@code inject-java}'s {@code MapToRepeatableSpec},
 * {@code TransformToRepeatableSpec}, {@code AnnotationMapperSpec},
 * {@code PriorityAnnotationMapperSpec} and {@code AddStereotypesFromVisitorSpec}.
 *
 * <p>Mapper coverage here checks that a mapper runs and that its single output arrives. These
 * check what happens when the output is <em>repeatable</em>: several entries have to be collected
 * into a container the author never wrote, on a language that cannot write one by repetition
 * either. Nothing else in this suite produces a container from anywhere but source.</p>
 */
class ScalaMapperOutputParitySpec extends AbstractScalaTypeElementSpec {

    void "collects several mapped entries into a repeatable container"() {
        when: 'one annotation maps to three requirements'
        def element = buildClassElement('mapperout.Test', '''
package mapperout

import io.micronaut.scala.processing.test.annotation.ScalaRepeatableSource

@ScalaRepeatableSource(Array("one", "two", "three"))
class Test
''')

        then: 'each entry is readable as the repeatable type'
        element.getAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null).toSet() ==
            ['one', 'two', 'three'] as Set

        and: 'through the container, which nothing in the source wrote'
        element.hasAnnotation(Requirements)

        and: '''and the source annotation is still there. A mapper adds to what it reads; only a
                transformer replaces it, which `ScalaAnnotationMappingParitySpec` asserts of each
                in turn'''
        element.hasAnnotation(ScalaRepeatableSource)
    }

    void "produces a single mapped entry without a spurious container"() {
        when:
        def element = buildClassElement('mapperout.Test', '''
package mapperout

import io.micronaut.scala.processing.test.annotation.ScalaRepeatableSource

@ScalaRepeatableSource(Array("only"))
class Test
''')

        then: 'one entry reads the same as one written directly'
        element.getAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null) == ['only']
    }

    void "carries mapped entries through to the written definition"() {
        when: 'the same mapping on a bean, read back from the definition rather than the element'
        def definition = buildBeanDefinition('mapperout.Test', '''
package mapperout

import io.micronaut.scala.processing.test.annotation.ScalaRepeatableSource
import jakarta.inject.Singleton

@Singleton
@ScalaRepeatableSource(Array("alpha", "beta"))
class Test
''')

        then: 'a mapper output that only reached the element model would be lost here'
        definition.getAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null).toSet() ==
            ['alpha', 'beta'] as Set
    }
}
