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

import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code EachPropertyNestingSpec},
 * {@code EachBeanParameterSpec} and {@code InterfaceNestingSpec}.
 *
 * <p>{@code @EachProperty} turns one declaration into one bean per configuration entry, named by
 * the key. Nesting one inside another makes the inner prefix depend on the outer bean's key,
 * which is resolved from the configuration path rather than from anything written in the source
 * -- so it is the case where a mistake in how prefixes are built shows up as beans that exist but
 * are bound from the wrong place.</p>
 *
 * <p>Configuration coverage here binds a fixed prefix. These bind a prefix the compiler cannot
 * know, through Scala's constructor binding, where the key arrives as a {@code @Parameter}
 * constructor argument alongside the bound properties.</p>
 */
class ScalaEachPropertyParitySpec extends AbstractScalaTypeElementSpec {

    void "creates one bean per configuration entry, named by its key"() {
        when:
        def context = buildContext('''
package eachproperty

import io.micronaut.context.annotation.EachProperty
import io.micronaut.context.annotation.Parameter

@EachProperty("servers")
class ServerConfig(@Parameter val name: String):
  var host: String = null
  var port: Int = 0
''', [
            'servers.one.host': 'first',
            'servers.one.port': '8080',
            'servers.two.host': 'second',
            'servers.two.port': '9090'
        ], true)
        def type = context.classLoader.loadClass('eachproperty.ServerConfig')

        then: 'one bean per key'
        context.getBeansOfType(type).size() == 2

        and: 'each named by its key and bound from its own subtree'
        def one = context.getBean(type, Qualifiers.byName('one'))
        one.name() == 'one'
        one.host() == 'first'
        one.port() == 8080
        context.getBean(type, Qualifiers.byName('two')).host() == 'second'

        cleanup:
        context?.close()
    }

    void "resolves a nested EachProperty prefix from the outer bean's key"() {
        when: 'the inner prefix is only known once the outer key is'
        def context = buildContext('''
package eachproperty

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.EachProperty
import io.micronaut.context.annotation.Parameter

@EachProperty("outer")
class OuterConfig(@Parameter val name: String):
  var label: String = null

  @ConfigurationProperties("inner")
  class InnerConfig:
    var value: String = null
''', [
            'outer.a.label': 'alpha',
            'outer.a.inner.value': 'nested-a',
            'outer.b.label': 'beta',
            'outer.b.inner.value': 'nested-b'
        ], true)
        def outerType = context.classLoader.loadClass('eachproperty.OuterConfig')

        then: 'the outer beans exist and are bound from their own subtrees'
        context.getBeansOfType(outerType).size() == 2
        context.getBean(outerType, Qualifiers.byName('a')).label() == 'alpha'
        context.getBean(outerType, Qualifiers.byName('b')).label() == 'beta'

        cleanup:
        context?.close()
    }

    void "drives an EachBean off each configuration bean"() {
        when: 'one client per server configuration, taking that configuration as a parameter'
        def context = buildContext('''
package eachproperty

import io.micronaut.context.annotation.EachBean
import io.micronaut.context.annotation.EachProperty
import io.micronaut.context.annotation.Parameter

@EachProperty("servers")
class ServerConfig(@Parameter val name: String):
  var host: String = null

@EachBean(classOf[ServerConfig])
class ServerClient(val config: ServerConfig):
  def describe(): String = config.name + "@" + config.host
''', [
            'servers.one.host': 'first',
            'servers.two.host': 'second'
        ], true)
        def clientType = context.classLoader.loadClass('eachproperty.ServerClient')

        then: 'one bean per configuration, each holding its own'
        context.getBeansOfType(clientType).size() == 2
        context.getBean(clientType, Qualifiers.byName('one')).describe() == 'one@first'
        context.getBean(clientType, Qualifiers.byName('two')).describe() == 'two@second'

        cleanup:
        context?.close()
    }
}
