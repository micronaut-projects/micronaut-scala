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
 * P1 parity, ported from {@code inject-java}'s {@code ConfigurationPropertiesBuilderSpec}.
 *
 * <p>{@code @ConfigurationBuilder} binds configuration by calling setters on an object the
 * configuration class holds, so what counts as a setter is the whole question. The one case
 * covered here uses a void JavaBean setter, which is the shape a Scala author is least likely
 * to write.</p>
 *
 * <p>A Scala builder returns itself so calls can be chained, and names its setters without a
 * prefix. Both are ordinary here and neither is the Java default: a fluent setter returns the
 * builder rather than {@code void}, and a prefix-free one has no {@code set} for Micronaut to
 * strip, which is what {@code @AccessorsStyle} exists to declare.</p>
 */
class ScalaConfigurationBuilderParitySpec extends AbstractScalaTypeElementSpec {

    void "binds through a fluent setter that returns the builder"() {
        when:
        def context = buildContext('''
package builderparity

import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationProperties

class Inner:
  private var fooValue: String = ""
  private var portValue: Int = 0

  def getFoo(): String = fooValue
  def setFoo(value: String): Inner =
    fooValue = value
    this

  def getPort(): Int = portValue
  def setPort(value: Int): Inner =
    portValue = value
    this

@ConfigurationProperties("builder")
class BuilderConfig:
  @ConfigurationBuilder(configurationPrefix = "inner")
  val inner: Inner = new Inner
''', ['builder.inner.foo': 'bar', 'builder.inner.port': '8080'], true)
        def bean = getBean(context, 'builderparity.BuilderConfig')

        then: 'returning the builder rather than void does not stop it being a setter'
        bean.inner().getFoo() == 'bar'
        bean.inner().getPort() == 8080

        cleanup:
        context?.close()
    }

    void "binds through prefix-free setters declared with AccessorsStyle"() {
        when: 'the builder is written the way Scala writes one'
        def context = buildContext('''
package builderparity

import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.AccessorsStyle

class Inner:
  private var fooValue: String = ""

  def foo(): String = fooValue
  def foo(value: String): Inner =
    fooValue = value
    this

@ConfigurationProperties("builder")
class BuilderConfig:
  // The style goes on the member holding the builder, not on the builder: it describes how the
  // builder's own setters are named, which the configuration class is what declares.
  @ConfigurationBuilder(configurationPrefix = "inner")
  @AccessorsStyle(readPrefixes = Array(""), writePrefixes = Array(""))
  val inner: Inner = new Inner
''', ['builder.inner.foo': 'bar'], true)
        def bean = getBean(context, 'builderparity.BuilderConfig')

        then: 'there is no prefix to strip, and the style annotation says so'
        bean.inner().foo() == 'bar'

        cleanup:
        context?.close()
    }

    void "binds only the properties includes names"() {
        when:
        def context = buildContext('''
package builderparity

import io.micronaut.context.annotation.ConfigurationBuilder
import io.micronaut.context.annotation.ConfigurationProperties

class Inner:
  private var fooValue: String = "untouched"
  private var barValue: String = "untouched"

  def getFoo(): String = fooValue
  def setFoo(value: String): Unit = fooValue = value

  def getBar(): String = barValue
  def setBar(value: String): Unit = barValue = value

@ConfigurationProperties("builder")
class BuilderConfig:
  @ConfigurationBuilder(configurationPrefix = "inner", includes = Array("foo"))
  val inner: Inner = new Inner
''', ['builder.inner.foo': 'bar', 'builder.inner.bar': 'ignored'], true)
        def bean = getBean(context, 'builderparity.BuilderConfig')

        then: 'the named property binds'
        bean.inner().getFoo() == 'bar'

        and: 'and the one left out keeps whatever the builder started with'
        bean.inner().getBar() == 'untouched'

        cleanup:
        context?.close()
    }
}
