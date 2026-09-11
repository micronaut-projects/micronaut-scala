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
 * P0 parity, ported from {@code inject-java}'s {@code NullableElementSpec}.
 *
 * <p>{@code @NullMarked} does not annotate anything itself: it changes what an <em>absent</em>
 * annotation means for everything inside it. Nullability coverage here is all of the explicit
 * kind, where a member says what it is, so the defaulting rule -- the part that decides what
 * every unannotated member means -- had no test at all.</p>
 *
 * <p>It is worth one in Scala because the rule is applied by walking outward from a member to
 * its enclosing declarations, and that walk crosses the places where a Scala declaration is
 * spread across several symbols. A member that reports neither nullable nor non-null under
 * {@code @NullMarked} is the failure to look for, and it is silent: it just means validation
 * and binding treat a value as unconstrained.</p>
 */
class ScalaNullMarkedParitySpec extends AbstractScalaTypeElementSpec {

    void "treats unannotated members as non-null inside a NullMarked class"() {
        when:
        def element = buildClassElement('nullmarked.Test', '''
package nullmarked

import org.jspecify.annotations.Nullable
import org.jspecify.annotations.NullMarked

@NullMarked
class Test:
  def emptyToNull(x: String): String | Null = if x.isEmpty then null else x

  def nullToEmpty(@Nullable x: String): String = if x == null then "" else x
''')
        def emptyToNull = element.findMethod('emptyToNull').get()
        def nullToEmpty = element.findMethod('nullToEmpty').get()

        then: 'an unannotated parameter is non-null because the class says so'
        emptyToNull.parameters[0].isNonNull()
        !emptyToNull.parameters[0].isNullable()

        and: 'and so is an unannotated return, read from the method'
        nullToEmpty.isNonNull()
        !nullToEmpty.isNullable()

        and: '''the mark is carried on the member and not on the type element the member returns.
                Java answers `getReturnType().isNonNull()` as well; that half is not implemented
                here, and is a smaller thing than it looks -- binding and validation ask the
                member, which is what the assertions above check. Doing it would mean deciding
                what a shared type element reports about a use site, which is the same question
                the type-argument annotation fix on this branch had to answer, and it should be
                answered the same way rather than twice'''
        !nullToEmpty.returnType.isNonNull()

        and: 'while an explicit @Nullable still wins over the default'
        nullToEmpty.parameters[0].isNullable()
        !nullToEmpty.parameters[0].isNonNull()
    }

    void "leaves members unmarked without NullMarked"() {
        when: 'the same class with the annotation removed'
        def element = buildClassElement('nullmarked.Test', '''
package nullmarked

class Test:
  def nullToEmpty(x: String): String = if x == null then "" else x
''')
        def method = element.findMethod('nullToEmpty').get()

        then: 'nothing is claimed in either direction, which is what makes the default visible'
        !method.isNonNull()
        !method.isNullable()
        !method.parameters[0].isNonNull()
        !method.parameters[0].isNullable()
    }

    void "applies NullMarked written on a method alone"() {
        when:
        def element = buildClassElement('nullmarked.Test', '''
package nullmarked

import org.jspecify.annotations.NullMarked

class Test:
  @NullMarked
  def marked(x: String): String = x

  def unmarked(x: String): String = x
''')
        def marked = element.findMethod('marked').get()
        def unmarked = element.findMethod('unmarked').get()

        then: 'the method it is written on defaults to non-null'
        marked.isNonNull()
        marked.parameters[0].isNonNull()

        and: 'and its neighbour is untouched'
        !unmarked.isNonNull()
        !unmarked.parameters[0].isNonNull()
    }

    void "treats an unannotated val as non-null inside a NullMarked class"() {
        when: 'a val, which is a field and an accessor -- the walk has to reach both'
        def element = buildClassElement('nullmarked.Test', '''
package nullmarked

import org.jspecify.annotations.Nullable
import org.jspecify.annotations.NullMarked

@NullMarked
class Test:
  val notNull: String = ""

  @Nullable
  val maybe: String = null
''')
        def notNull = element.beanProperties.find { it.name == 'notNull' }
        def maybe = element.beanProperties.find { it.name == 'maybe' }

        then:
        notNull.isNonNull()
        !notNull.isNullable()

        and:
        maybe.isNullable()
        !maybe.isNonNull()
    }
}
