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

    void "rejects a member the introspection cannot reach"() {
        when: '''a Scala field is always private, so a field-access property is unreachable by
                 construction rather than by choice -- Java has to write `private` to get here'''
        buildBeanIntrospection('introspectedproperty.Inaccessible', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Inaccessible:
  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ))
  private val name: String = null
''')

        then: '''the declaration asks for something impossible and is refused, rather than
                 being dropped in silence -- which is what happened while the fields were only
                 handed to core when the query asked for field access'''
        def e = thrown(RuntimeException)
        e.message.contains('cannot be used as an introspected property')
    }

    void "accepts the annotation on a var, whose field is private and whose accessors are generated"() {
        when: '''the Scala shape the unreachable case has to be told apart from: `var name` is one
                 declaration the compiler expands into a private field and a pair of accessors, so
                 the field is no more accessible than the rejected one -- what makes it a property
                 is the accessors that come with it'''
        def introspection = buildBeanIntrospection('introspectedproperty.Reachable', '''
package introspectedproperty

import io.micronaut.core.annotation.Introspected

@Introspected
class Reachable:
  @Introspected.Property(accessKind = Array(Introspected.Property.Access.READ, Introspected.Property.Access.WRITE))
  var name: String = null
''')
        def instance = introspection.instantiate()

        then:
        introspection.propertyNames.toList() == ['name']

        when:
        def property = introspection.getRequiredProperty('name', String)
        property.set(instance, 'set')

        then:
        property.get(instance) == 'set'
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
