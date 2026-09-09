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
 * P1 parity, ported from {@code inject-java}'s {@code PropertyElementSpec}.
 *
 * <p>{@code @Introspected.Property} declares how one member takes part in an introspection, and
 * core rejects the declarations that cannot mean anything: two halves of a property claiming
 * different access, a name given twice differently, or a member the introspection could not
 * reach. Introspection coverage here asserts what is produced, never what is refused, and a
 * validation that silently stops firing is invisible to a test that only checks the good case.
 * </p>
 *
 * <p>The Scala interest is where the annotation lands. It has to be found on the member core
 * checks, and Scala spreads a declaration across a constructor parameter, a private field and an
 * accessor -- the same spread that hid {@code @NonBinding} from interceptor binding until it was
 * fixed on this branch. These are the diagnostics that say whether it is being found.</p>
 */
class ScalaIntrospectedPropertyParitySpec extends AbstractScalaTypeElementSpec {

    void "rejects two accessors of one property claiming different access"() {
        when: 'the reader says READ and the writer says WRITE'
        buildBeanIntrospection('introspectedproperty.Conflicting', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Conflicting:
  private var backing: String = null

  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ))
  def getName(): String = backing

  @Introspected.Property(accessKind = Array(Introspected.Property.Access.WRITE))
  def setName(name: String): Unit = backing = name
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('Conflicting @Introspected.Property accessKind declarations for property [name]')
    }

    void "rejects a property whose value and name disagree"() {
        when:
        buildBeanIntrospection('introspectedproperty.Mismatched', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Mismatched:
  private var backing: String = null

  @Introspected.Property(value = "external_name", name = "other_name")
  def getName(): String = backing
''')

        then:
        def e = thrown(RuntimeException)
        e.message.contains('The @Introspected.Property value and name members must match when both are declared')
    }

    void "silently ignores a member the introspection cannot reach"() {
        when: '''a Scala field is always private, so a field-access property is unreachable by
                 construction rather than by choice -- Java has to write `private` to get here'''
        def introspection = buildBeanIntrospection('introspectedproperty.Inaccessible', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Inaccessible:
  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ))
  private val name: String = null
''')

        then: '''no property, and no diagnostic. Java raises "the field is not accessible for
                 visibility [DEFAULT]" here, so this is a divergence and is pinned as one.

                 Where it stops is known: the field element does carry the annotation --
                 `ALL_FIELDS` reports `name` with `Introspected$Property` on it, and a private
                 `val` generates no accessor at all, so there is no method for it to have landed
                 on instead. It is not reaching the field supplier
                 `AstBeanPropertiesUtils` iterates, which is where
                 `validateIntrospectedPropertyField` is called from. That is the lead for
                 anyone fixing this, and it is a missing diagnostic rather than a wrong
                 result: the annotation asks for something impossible either way.'''
        introspection.propertyNames.length == 0
    }

    void "accepts an accessor pair that agrees on access"() {
        when: 'the same shape as the conflicting case, with the halves in agreement'
        def introspection = buildBeanIntrospection('introspectedproperty.Agreeing', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Agreeing:
  private var backing: String = null

  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ, Introspected.Property.Access.WRITE))
  def getName(): String = backing

  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ, Introspected.Property.Access.WRITE))
  def setName(name: String): Unit = backing = name
''')
        def instance = introspection.instantiate()

        then: 'the annotation was found on both halves, so nothing conflicted and both are wired'
        introspection.propertyNames.toList() == ['name']

        when:
        def property = introspection.getRequiredProperty('name', String)
        property.set(instance, 'set')

        then:
        property.get(instance) == 'set'
    }
}
