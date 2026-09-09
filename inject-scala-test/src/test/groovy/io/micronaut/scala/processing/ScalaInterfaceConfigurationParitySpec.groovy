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

import io.micronaut.context.annotation.Property
import io.micronaut.runtime.context.env.ConfigurationAdvice
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code InterfaceConfigurationPropertiesSpec}.
 *
 * <p>{@code @ConfigurationProperties} on an interface is not the class form with a different
 * receiver: there is nothing to bind into, so each accessor is turned into introduction advice
 * that reads its own property, and the property path comes from the accessor's <em>name</em>
 * rather than from a field. Every configuration case covered here so far binds into a class,
 * where the name is only ever a fallback.</p>
 *
 * <p>Which makes the Scala question the naming one. A trait's abstract accessor is written
 * {@code def host: String}, with no {@code get} prefix for Micronaut to strip, so the two
 * spellings are tested against each other: the JavaBean form, and the Scala form declared with
 * {@code @AccessorsStyle}.</p>
 */
class ScalaInterfaceConfigurationParitySpec extends AbstractScalaTypeElementSpec {

    void "resolves configuration through advice for a JavaBean-style trait"() {
        when:
        def context = buildContext('''
package interfaceconfig

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.bind.annotation.Bindable

@ConfigurationProperties("foo.bar")
trait MyConfig:
  def getHost(): String
  def getServerPort(): Int

  @Bindable(defaultValue = "true")
  def isEnabled(): Boolean
''', ['foo.bar.host': 'test', 'foo.bar.server-port': '9999'], true)
        def definition = getBeanDefinition(context, 'interfaceconfig.MyConfig')

        then: 'the trait is implemented by advice rather than instantiated'
        definition.annotationMetadata.getAnnotationType(ConfigurationAdvice.name).present

        and: 'each accessor carries the property path its name resolves to'
        definition.getRequiredMethod('getHost').stringValue(Property, 'name').get() == 'foo.bar.host'
        definition.getRequiredMethod('getServerPort').stringValue(Property, 'name').get() == 'foo.bar.server-port'

        when:
        def config = getBean(context, 'interfaceconfig.MyConfig')

        then:
        config.getHost() == 'test'
        config.getServerPort() == 9999

        and: 'an unset property falls back to the declared default'
        config.isEnabled()

        cleanup:
        context?.close()
    }

    void "resolves configuration through advice for a prefix-free Scala trait"() {
        when: 'the accessors are spelled the Scala way, with no get prefix to strip'
        def context = buildContext('''
package interfaceconfig

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.AccessorsStyle
import io.micronaut.core.bind.annotation.Bindable

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = Array(""))
trait MyConfig:
  def host: String
  def serverPort: Int

  @Bindable(defaultValue = "true")
  def enabled: Boolean
''', ['foo.bar.host': 'test', 'foo.bar.server-port': '9999'], true)
        def definition = getBeanDefinition(context, 'interfaceconfig.MyConfig')

        then: 'the whole method name is the property, not a name with a prefix removed'
        definition.annotationMetadata.getAnnotationType(ConfigurationAdvice.name).present
        definition.getRequiredMethod('host').stringValue(Property, 'name').get() == 'foo.bar.host'
        definition.getRequiredMethod('serverPort').stringValue(Property, 'name').get() == 'foo.bar.server-port'

        when:
        def config = getBean(context, 'interfaceconfig.MyConfig')

        then:
        config.host() == 'test'
        config.serverPort() == 9999
        config.enabled()

        cleanup:
        context?.close()
    }

    void "binds an absent property on a trait accessor to None"() {
        when: 'server-port is deliberately not set'
        def context = buildContext('''
package interfaceconfig

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.AccessorsStyle

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = Array(""))
trait MyConfig:
  def host: String
  def serverPort: Option[Int]
''', ['foo.bar.host': 'test'], true)
        def config = getBean(context, 'interfaceconfig.MyConfig')

        then: 'Option stands in for Optional, as it does on a configuration class'
        config.host() == 'test'
        config.serverPort().isEmpty()

        when:
        def present = buildContext('''
package interfaceconfig2

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.AccessorsStyle

@ConfigurationProperties("foo.bar")
@AccessorsStyle(readPrefixes = Array(""))
trait MyConfig:
  def serverPort: Option[Int]
''', ['foo.bar.server-port': '9999'], true)

        then:
        getBean(present, 'interfaceconfig2.MyConfig').serverPort().get() == 9999

        cleanup:
        context?.close()
        present?.close()
    }
}
