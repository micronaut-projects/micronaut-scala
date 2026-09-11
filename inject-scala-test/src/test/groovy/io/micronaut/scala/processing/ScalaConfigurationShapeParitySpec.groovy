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
 * P1 parity, ported from {@code inject-java}'s {@code InheritedConfigurationReaderPrefixSpec},
 * {@code ConfigurationPropertiesInjectSpec}, {@code ConfigPropertiesParseSpec} and
 * {@code ExternalConfigurationSpec}.
 *
 * <p>A configuration class is two things at once: a set of properties bound from a prefix, and
 * an ordinary bean that can have dependencies. The prefix is what most of these turn on -- it is
 * composed from the class and everything it inherits, so a member bound from the wrong prefix
 * still binds, just from somewhere nobody wrote.</p>
 *
 * <p>Prefix composition itself is covered by {@code ScalaConfigurationParitySpec}, which also
 * records the rule a draft of this spec got backwards: an inherited property keeps the prefix of
 * the class that <em>declares</em> it rather than taking the subclass's composed one. What is
 * added here is the bean half -- a dependency resolved beside the bound properties, through the
 * constructor, which is the mechanism core documents rather than an {@code @Inject} member -- and
 * the negative case that gives the prefix assertions their meaning.</p>
 */
class ScalaConfigurationShapeParitySpec extends AbstractScalaTypeElementSpec {

    void "takes a bean and a bound property through one configuration constructor"() {
        when: '''the constructor mixes configuration with dependencies, which is how core
                 documents it -- `@ConfigurationInject` on a constructor rather than `@Inject` on
                 a member, and the form Scala reaches first anyway'''
        def context = buildContext('''
package configshape

import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.inject.Singleton

@Singleton
class Helper:
  def help(): String = "helped"

@ConfigurationProperties("app")
class AppConfig(val name: String, val helper: Helper)
''', ['app.name': 'bound'], true)
        def config = getBean(context, 'configshape.AppConfig')

        then: 'the property is bound and the dependency resolved, on the same bean'
        config.name() == 'bound'
        config.helper().help() == 'helped'

        cleanup:
        context?.close()
    }

    void "binds a configuration class whose properties are constructor parameters"() {
        when: 'an immutable configuration, which is the idiomatic Scala form'
        def context = buildContext('''
package configshape

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("immutable")
class ImmutableConfig(val host: String, val port: Int)
''', ['immutable.host': 'localhost', 'immutable.port': '8080'], true)
        def config = getBean(context, 'configshape.ImmutableConfig')

        then:
        config.host() == 'localhost'
        config.port() == 8080

        cleanup:
        context?.close()
    }

    void "does not bind a property that belongs to a different prefix"() {
        when: 'a property named like the member but under another prefix'
        def context = buildContext('''
package configshape

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
class AppConfig:
  var name: String = "untouched"
''', ['other.name': 'wrong', 'app.other': 'also-wrong'], true)

        then: 'neither near miss binds, which is what makes the prefix assertions mean anything'
        getBean(context, 'configshape.AppConfig').name() == 'untouched'

        cleanup:
        context?.close()
    }
}
