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
 * P0 parity, ported from {@code inject-java}'s {@code ClassElementSpec} "test duplicate methods".
 *
 * <p>A trait that overrides a method it inherits, mixed into a class, offers the same method
 * twice -- once from each level. The class has to report it once. Method coverage here asks
 * whether a method is found and what it says; a duplicate satisfies both, so nothing established
 * that the list has no repeats, and the walk that produces it was rewritten on this branch.</p>
 *
 * <p>The shape is Java's, but it is Scala's default idiom rather than a corner: an override in a
 * trait that calls the one it overrides is how behaviour gets layered, and generic traits stack
 * the same way. The generic parameter matters -- {@code update(String, Id)} erases to
 * {@code update(String, Object)}, so the override and its bridge look alike to anything
 * comparing erased signatures.</p>
 */
class ScalaDuplicateMethodParitySpec extends AbstractScalaTypeElementSpec {

    void "reports a method a trait overrides from its own parent once"() {
        when:
        def element = buildClassElement('duplicates.MyBean', '''
package duplicates

trait Parent[Id]:
  def update(request: String, id: Id): String = "ok"

trait Middle[Id] extends Parent[Id]:
  override def update(request: String, id: Id): String = super.update(request, id)

class MyBean extends Middle[String]:
  def test(): Unit = ()
''')

        then:
        element.methods*.name.sort() == ['test', 'update']
    }

    void "reports a method reached through two traits once"() {
        when: 'a diamond -- both mixins extend the same base, which declares the method'
        def element = buildClassElement('duplicates.MyBean', '''
package duplicates

trait Base:
  def describe(): String = "base"

trait Reading extends Base
trait Writing extends Base

class MyBean extends Reading, Writing:
  def test(): Unit = ()
''')

        then:
        element.methods*.name.sort() == ['describe', 'test']
    }

    void "keeps overloads that a duplicate check must not collapse"() {
        when: 'same name, different arity and different erasure'
        def element = buildClassElement('duplicates.MyBean', '''
package duplicates

trait Parent[Id]:
  def update(request: String, id: Id): String = "ok"
  def update(request: String): String = "one"

class MyBean extends Parent[String]
''')

        then: 'both survive, so de-duplication is by signature and not by name'
        element.methods.findAll { it.name == 'update' }.size() == 2
        element.methods.findAll { it.name == 'update' }*.parameters*.length.sort() == [1, 2]
    }
}
