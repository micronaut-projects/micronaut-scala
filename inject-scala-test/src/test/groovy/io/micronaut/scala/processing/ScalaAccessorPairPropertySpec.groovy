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
 * A property written as a pair of methods rather than as a {@code val} or a {@code var}.
 *
 * <p>{@code def size} with {@code def size_=} is how Scala spells a property whose storage is
 * not a field -- computed, delegated, or held by something the class does not own. Both halves
 * were visible as ordinary methods, so injection through the setter worked while
 * {@code @Introspected} exposed no property, and the identical {@code var} exposed one. The
 * guide recorded that as a limitation.</p>
 */
class ScalaAccessorPairPropertySpec extends AbstractScalaTypeElementSpec {

    void "assembles a hand-written accessor pair into a property"() {
        when:
        def introspection = buildBeanIntrospection('accessorpair.Holder', '''
package accessorpair

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  private var backing: Int = 0

  def size: Int = backing
  def size_=(value: Int): Unit = backing = value
''')
        def instance = introspection.instantiate()

        then:
        introspection.propertyNames.toList() == ['size']

        when:
        def property = introspection.getRequiredProperty('size', Integer.TYPE)
        property.set(instance, 42)

        then: 'both halves are wired, so the property reads and writes through the methods'
        !property.isReadOnly()
        property.get(instance) == 42
    }

    void "leaves a lone reader as a method"() {
        when: 'no setter, so nothing distinguishes the reader from any other no-argument method'
        def introspection = buildBeanIntrospection('accessorpair.Holder', '''
package accessorpair

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  def size: Int = 3
''')

        then: '''a property is not invented from a name alone -- @AccessorsStyle remains the way
                 to declare that prefix-free reads are accessors'''
        introspection.propertyNames.length == 0
    }

    void "does not pair a setter whose type does not match the reader"() {
        when: 'size_= takes a String while size returns an Int'
        def introspection = buildBeanIntrospection('accessorpair.Holder', '''
package accessorpair

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  private var backing: Int = 0

  def size: Int = backing
  def size_=(value: String): Unit = backing = value.length
''')

        then: 'it is an unrelated method that happens to be named for one'
        introspection.propertyNames.length == 0
    }

    void "still exposes the equivalent var as one property"() {
        when: 'the same shape written as a var, which already worked'
        def introspection = buildBeanIntrospection('accessorpair.Holder', '''
package accessorpair

import io.micronaut.core.annotation.Introspected

@Introspected
class Holder:
  var size: Int = 0
''')

        then: 'the pair handling does not double it up'
        introspection.propertyNames.toList() == ['size']
    }
}
