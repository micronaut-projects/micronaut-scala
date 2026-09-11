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

import java.util.function.BiFunction
import java.util.function.Function

/**
 * P1 parity, ported from {@code inject-java}'s {@code ExecutableAnnotationOnFactorySpec}.
 *
 * <p>Type arguments on a factory-produced type are asserted here by their <em>type</em> only.
 * The generated definition records their declared <em>names</em> too, and the name is what a
 * caller resolving a placeholder looks the argument up by, so a name lost between the model and
 * the definition is invisible to every assertion made so far -- the types stay right while the
 * lookup that needs {@code "T"} stops finding anything.</p>
 *
 * <p>The produced type here is a Java functional interface, which Scala satisfies with a lambda
 * through SAM conversion rather than by naming the interface. So the type arguments have to be
 * recovered from the interface the lambda was converted to, not from anything written at the
 * expression, and {@code @Executable} then has to find the SAM method on it.</p>
 */
class ScalaExecutableFunctionFactoryParitySpec extends AbstractScalaTypeElementSpec {

    void "records the names and types of a produced functional interface's arguments"() {
        when:
        def definition = buildGeneratedBeanDefinition('funcfactory', '$Shop$MyFunc0$Definition', '''
package funcfactory

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory

@Factory
class Shop:
  @Bean
  @Executable
  def myFunc(): java.util.function.Function[String, java.lang.Integer] =
    (value: String) => Integer.valueOf(value.length)
''')

        then:
        definition != null

        and: 'the argument names are the ones Function declares, not positional placeholders'
        definition.getTypeArguments(Function).size() == 2
        definition.getTypeArguments(Function)*.name == ['T', 'R']

        and: 'and they carry the types the lambda was converted at'
        definition.getTypeArguments(Function)*.type == [String, Integer]

        and: 'the single abstract method is executable, found through the interface'
        definition.findMethod('apply', String).present
    }

    void "resolves a produced interface's arguments through its own supertype"() {
        when: 'BiFunction declares three arguments, and the produced type fixes all of them'
        def definition = buildGeneratedBeanDefinition('funcfactory', '$Shop$Combine0$Definition', '''
package funcfactory

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory

@Factory
class Shop:
  @Bean
  @Executable
  def combine(): java.util.function.BiFunction[String, java.lang.Integer, java.lang.Boolean] =
    (text: String, size: java.lang.Integer) => java.lang.Boolean.valueOf(text.length == size)
''')

        then:
        definition != null
        definition.getTypeArguments(BiFunction)*.name == ['T', 'U', 'R']
        definition.getTypeArguments(BiFunction)*.type == [String, Integer, Boolean]
        definition.findMethod('apply', String, Integer).present
    }
}
