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
import spock.lang.PendingFeature

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
 * <p>Two of the four are marked pending. The configuration case works, so the validation visitor
 * is being discovered and run; what does not reach it is a constraint written directly on a
 * constructor or executable parameter, which is where Scala puts most of them.</p>
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

    @PendingFeature(reason = 'micronaut-validation-processor is on the compilation classpath and registers ValidationVisitor through the service loader, and a constrained configuration property does mark the definition validated -- so the visitor runs. A constraint written directly on a constructor or executable parameter does not reach it')
    void "marks a bean with a constrained constructor parameter as validated"() {
        when: 'the constraint is on a constructor parameter, which is where Scala puts them'
        def definition = buildBeanDefinition('validation.Service', '''
package validation

import jakarta.inject.Singleton
import jakarta.validation.constraints.NotBlank

@Singleton
class Service(@NotBlank val name: String)
''')

        then:
        definition instanceof ValidatedBeanDefinition
    }

    @PendingFeature(reason = 'micronaut-validation-processor is on the compilation classpath and registers ValidationVisitor through the service loader, and a constrained configuration property does mark the definition validated -- so the visitor runs. A constraint written directly on a constructor or executable parameter does not reach it')
    void "marks a bean with a constrained executable parameter as validated"() {
        when:
        def definition = buildBeanDefinition('validation.Service', '''
package validation

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.constraints.Min

@Singleton
class Service:
  @Executable
  def check(@Min(value = 10) size: Int): Int = size
''')

        then:
        definition instanceof ValidatedBeanDefinition
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
