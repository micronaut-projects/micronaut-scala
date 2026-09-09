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

import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code RequiresBeanPropertiesSpec}.
 *
 * <p>{@code @Requires(bean = ..., beanProperty = ...)} decides whether a bean exists by reading a
 * property off <em>another</em> bean at condition time. Requirement coverage here is all of the
 * property-source kind, which reads configuration directly; this reads it back through the
 * property model, so a property the model does not expose makes the condition fail rather than
 * report anything.</p>
 *
 * <p>That is what makes it worth a Scala test rather than trusting the Java one. Which members
 * count as properties is the question this branch has moved twice -- constructor parameters of a
 * {@code @ConfigurationProperties} class, and hand-written accessor pairs -- and a condition is
 * where getting it wrong stops a bean from existing at all, with no error to read.</p>
 */
class ScalaRequiresBeanPropertyParitySpec extends AbstractScalaTypeElementSpec {

    private static String source(String config) {
        '''
package requiresprop

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

''' + config + '''

@Singleton
@Requires(bean = classOf[Config], beanProperty = "property")
class PresentDependant

@Singleton
@Requires(bean = classOf[Config], beanProperty = "property", notEquals = "disabled")
class NotDisabledDependant
'''
    }

    private static final String VAR_CONFIG = '''
@ConfigurationProperties("config")
class Config:
  var property: String = null
'''

    void "loads a bean whose required bean property is set"() {
        when:
        def context = buildContext(source(VAR_CONFIG), ['config.property': 'anyValue'], true)

        then:
        getBean(context, 'requiresprop.PresentDependant') != null

        cleanup:
        context?.close()
    }

    void "does not load a bean whose required bean property is absent"() {
        when: 'the configuration bean exists but the property was never set'
        def context = buildContext(source(VAR_CONFIG), [:], true)
        getBean(context, 'requiresprop.PresentDependant')

        then:
        thrown(NoSuchBeanException)

        cleanup:
        context?.close()
    }

    void "honours notEquals against the value the property holds"() {
        when: 'the value is the one the requirement excludes'
        def excluded = buildContext(source(VAR_CONFIG), ['config.property': 'disabled'], true)
        getBean(excluded, 'requiresprop.NotDisabledDependant')

        then:
        thrown(NoSuchBeanException)

        when: 'and any other value satisfies it'
        def allowed = buildContext(source(VAR_CONFIG), ['config.property': 'enabled'], true)

        then:
        getBean(allowed, 'requiresprop.NotDisabledDependant') != null

        cleanup:
        excluded?.close()
        allowed?.close()
    }

    void "reads a required property from a hand-written accessor pair"() {
        when: '''the configuration exposes the property as a def pair rather than a var, which
                 is a property at all only because of the accessor-pair support on this branch'''
        def context = buildContext(source('''
@ConfigurationProperties("config")
class Config:
  private var backing: String = null

  def property: String = backing
  def property_=(value: String): Unit = backing = value
'''), ['config.property': 'anyValue'], true)

        then:
        getBean(context, 'requiresprop.PresentDependant') != null

        cleanup:
        context?.close()
    }
}
