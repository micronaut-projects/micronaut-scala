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
 * P1 configuration parity, covering the three shapes {@code DISABLED_TESTS.md} lists that
 * nothing else here exercises: configuration declared on a trait, a prefix inherited from a
 * configuration superclass, and {@code @ConfigurationBuilder}.
 *
 * <p>All three already work. They are worth pinning because each is bound at runtime from
 * metadata this plugin writes, so a change to how properties or inherited members are
 * modelled breaks them silently rather than at compile time.</p>
 */
class ScalaConfigurationParitySpec extends AbstractScalaTypeElementSpec {

    void 'configuration can be declared on a trait'() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("iface")
trait IfaceConfig {
  def getHost(): String
  def getPort(): Int
}
''', ['iface.host': 'localhost', 'iface.port': '8080'], true)

        when: 'core synthesises an implementation from the getters'
        def bean = getBean(context, 'test.IfaceConfig')

        then:
        bean.getHost() == 'localhost'
        bean.getPort() == 8080

        cleanup:
        context?.close()
    }

    void 'an inherited property binds under the prefix of the class that declares it'() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("parent")
class ParentConfig {
  var shared: String = ""
}

@ConfigurationProperties("child")
class ChildConfig extends ParentConfig {
  var own: String = ""
}
''', ['parent.shared': 'from-parent', 'parent.child.shared': 'from-child', 'parent.child.own': 'own-value'], true)

        when:
        def child = getBean(context, 'test.ChildConfig')

        then: 'the subclass prefix nests, so its own property is parent.child.own'
        child.own() == 'own-value'

        and: 'but an inherited property keeps the declaring class prefix, so parent.shared'
        child.shared() == 'from-parent'

        cleanup:
        context?.close()
    }

    void 'a ConfigurationBuilder binds through the builder object'() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationProperties

class Inner {
  private var foo: String = ""
  def getFoo(): String = foo
  def setFoo(f: String): Unit = { foo = f }
}

@ConfigurationProperties("builder")
class BuilderConfig {
  @ConfigurationBuilder(configurationPrefix = "inner")
  val inner: Inner = new Inner()
}
''', ['builder.inner.foo': 'bar'], true)

        when:
        def bean = getBean(context, 'test.BuilderConfig')

        then:
        bean.inner().getFoo() == 'bar'

        cleanup:
        context?.close()
    }
}
