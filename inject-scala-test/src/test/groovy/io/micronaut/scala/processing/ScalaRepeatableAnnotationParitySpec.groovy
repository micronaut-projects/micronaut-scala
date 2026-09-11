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

import io.micronaut.context.annotation.Requires
import io.micronaut.scala.processing.fixtures.Location
import io.micronaut.scala.processing.fixtures.Locations
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P0 parity, ported from the repeatable-annotation specs in {@code inject-java}:
 * {@code RepeatableAnnotationSpec}, {@code AddsRepeatableAnnotationSpec},
 * {@code AddsUnseenRepeatableAnnotationSpec} and {@code ReplacesRepeatableAnnotationSpec}.
 *
 * <p>Scala has no repeatable annotations. Writing one twice is an error, so every case those
 * specs express by repeating an annotation has to be expressed here by writing the container --
 * and a container written by hand takes a different path through the metadata builder than one
 * the compiler synthesises. That is the whole reason to port them rather than assume.</p>
 *
 * <p>The reading end is what most code depends on: {@code getAnnotationValuesByType} has to
 * return the entries whether they arrived as a container from source, as a container an author
 * wrote around a single entry, or from a visitor that added them after the fact.</p>
 */
class ScalaRepeatableAnnotationParitySpec extends AbstractScalaTypeElementSpec {

    void "expands a container written by hand into its entries"() {
        when:
        def definition = buildBeanDefinition('repeatable.Test', '''
package repeatable

import io.micronaut.scala.processing.fixtures.Location
import io.micronaut.scala.processing.fixtures.Locations
import jakarta.inject.Singleton

@Singleton
@Locations(Array(new Location("north"), new Location("south")))
class Test
''')

        then: 'the entries are readable as the repeatable type, not only as the container'
        definition.getAnnotationValuesByType(Location)*.stringValue()*.get().toSet() == ['north', 'south'] as Set

        and: 'and the container is present too, as it is what was written'
        definition.hasAnnotation(Locations)
    }

    void "expands a container holding a single entry"() {
        when: 'the shape Java would write as one bare annotation'
        def definition = buildBeanDefinition('repeatable.Test', '''
package repeatable

import io.micronaut.scala.processing.fixtures.Location
import io.micronaut.scala.processing.fixtures.Locations
import jakarta.inject.Singleton

@Singleton
@Locations(Array(new Location("only")))
class Test
''')

        then: 'one entry through a container reads the same as one written directly'
        definition.getAnnotationValuesByType(Location)*.stringValue()*.get() == ['only']
    }

    void "reads a single entry written without a container"() {
        when: 'the repeatable annotation written once, which Scala does allow'
        def definition = buildBeanDefinition('repeatable.Test', '''
package repeatable

import io.micronaut.scala.processing.fixtures.Location
import jakarta.inject.Singleton

@Singleton
@Location("solo")
class Test
''')

        then:
        definition.getAnnotationValuesByType(Location)*.stringValue()*.get() == ['solo']
    }

    void "keeps entries of a Micronaut repeatable separate on a class and its members"() {
        when: '''@Requires is repeatable, so the class-level container and a member-level entry
                 have to merge rather than one replacing the other'''
        def definition = buildBeanDefinition('repeatable.Test', '''
package repeatable

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Requirements
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requirements(Array(
  new Requires(property = "foo"),
  new Requires(property = "bar")
))
class Test:
  @Executable
  @Requires(property = "xyz")
  def run(): String = "ok"
''')

        then: 'the class keeps both of its own'
        definition.getAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null).toSet() ==
            ['foo', 'bar'] as Set

        and: 'and the member keeps its own alongside the ones it inherits from the class'
        definition.getRequiredMethod('run').getAnnotationValuesByType(Requires)
            *.stringValue('property')*.orElse(null).toSet() == ['foo', 'bar', 'xyz'] as Set
    }
}
