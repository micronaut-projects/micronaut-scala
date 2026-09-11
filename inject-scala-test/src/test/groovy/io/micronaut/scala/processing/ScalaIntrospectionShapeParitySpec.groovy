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
 * P1 introspection parity for three shapes the gate reported uncovered: method-only access
 * kind, introspection declared on a trait, and a property inherited from a trait.
 *
 * <p>Field access, include/exclude, covariant properties, numbered names, validation
 * metadata and generic placeholders are already covered elsewhere and are not restated
 * here.</p>
 */
class ScalaIntrospectionShapeParitySpec extends AbstractScalaTypeElementSpec {

    void 'METHOD access kind reads through accessors'() {
        given:
        def introspection = buildBeanIntrospection('test.Test', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected(accessKind = Array(Introspected.AccessKind.METHOD))
class Test {
  var direct: String = ""
  def getComputed(): String = "c"
}
''')
        def properties = introspection.getBeanProperties().collectEntries { [it.getName(), it] }

        expect: 'a var is reached through its generated accessor pair, so it is writable'
        properties.direct != null
        !properties.direct.isReadOnly()

        and: 'and a bare getter is a read-only property'
        properties.computed.isReadOnly()
    }

    void 'a trait can be introspected'() {
        given:
        def introspection = buildBeanIntrospection('test.Api', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
trait Api {
  def getName(): String
  def getAge(): Int
}
''')

        expect:
        introspection.getBeanProperties()*.getName().toSet() == ['name', 'age'] as Set

        and: 'a trait declares no setters, so every property is read-only'
        introspection.getBeanProperties().every { it.isReadOnly() }
    }

    void 'a property inherited from a trait is introspected'() {
        given:
        def introspection = buildBeanIntrospection('test.Impl', '''
package test

import io.micronaut.core.annotation.Introspected

trait Named {
  def getName(): String
}

@Introspected
class Impl(val name: String) extends Named {
  override def getName(): String = name
}
''')

        expect:
        introspection.getBeanProperties()*.getName() == ['name']

        and: 'and it reads back off a real instance'
        introspection.getRequiredProperty('name', String).get(introspection.instantiate('x')) == 'x'
    }
}
