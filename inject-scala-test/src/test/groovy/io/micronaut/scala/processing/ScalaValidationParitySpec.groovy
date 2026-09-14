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

import io.micronaut.inject.ValidatedBeanDefinition
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code ValidatedConfigurationSpec},
 * {@code ValidatedNonBeanSpec} and {@code ValidatedParseSpec}.
 *
 * <p>A constraint on a member makes the definition validated, and that decision is made when the
 * definition is written: {@code ValidatedBeanDefinition} is an interface the generated class
 * either implements or does not. Constraint coverage here asserts that an annotation is present
 * on an element; this asserts what the writer did with it.</p>
 *
 * <p>The negative half matters as much. A class with no constraints must <em>not</em> be
 * validated, or every bean pays for validation it never asked for, and nothing about its
 * behaviour would reveal it.</p>
 *
 * <p>Which mechanism applies depends on where the constraint is. A constrained property or a
 * constrained value-bound parameter is validated at the injection point, so the definition
 * implements {@code ValidatedBeanDefinition}; a constrained <em>executable</em> parameter is
 * validated by {@code @Validated} advice instead, so the method carries the stereotype and the
 * bean is proxied. Asserting the first for the second is the mistake a draft of this spec made,
 * and it read as a missing feature rather than a wrong assertion.</p>
 */
class ScalaValidationParitySpec extends AbstractScalaTypeElementSpec {

    void "marks a configuration class with a constrained property as validated"() {
        when:
        def definition = buildBeanDefinition('validation.AppConfig', '''
package validation

import io.micronaut.context.annotation.ConfigurationProperties
import jakarta.validation.constraints.NotBlank

@ConfigurationProperties("app")
class AppConfig:
  @NotBlank
  var name: String = null
''')

        then:
        definition instanceof ValidatedBeanDefinition
    }

    void "validates a constrained @Value parameter through the injection point"() {
        when: '''a constrained parameter bound from a property rather than from a bean. Core
                 validates these at the injection point, which is what lets them work without
                 @Introspected'''
        def definition = buildBeanDefinition('validation.Service', '''
package validation

import io.micronaut.context.annotation.Value
import jakarta.inject.Singleton
import jakarta.validation.constraints.NotBlank

@Singleton
class Service(@Value("${app.name}") @NotBlank val name: String)
''')

        then:
        definition instanceof ValidatedBeanDefinition
    }

    void "applies validation advice to a constrained executable method"() {
        when: '''a constraint on an executable parameter, which core validates through @Validated
                 advice rather than through the definition -- the bean is proxied and the method
                 carries the stereotype'''
        def context = buildContext('''
package validation

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.constraints.NotBlank

@Singleton
class Service:
  @Executable
  def setName(@NotBlank name: String): Unit = ()
''', [:], true)
        def definition = getBeanDefinition(context, 'validation.Service')

        then: 'the method is advised for validation'
        definition.findMethod('setName', String).get()
            .hasStereotype('io.micronaut.validation.Validated')

        cleanup:
        context?.close()
    }

    void "does not mark an unconstrained bean as validated"() {
        when: 'the same shape with the constraint removed'
        def definition = buildBeanDefinition('validation.Service', '''
package validation

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Singleton
class Service(val name: String = "x"):
  @Executable
  def check(size: Int): Int = size
''')

        then: 'validation is opted into by a constraint, not applied to everything'
        !(definition instanceof ValidatedBeanDefinition)
    }
}
