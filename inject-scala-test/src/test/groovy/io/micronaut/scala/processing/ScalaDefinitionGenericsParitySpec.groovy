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
 * First of the P1 parity ports, from the Java module's
 * {@code io.micronaut.inject.beans.BeanDefinitionSpec} -- "isTypeVariable" and the
 * "repeatable inner type annotation" family.
 *
 * <p>P0 asked what the element model reports. This asks what actually reaches the generated
 * bean definition, which is a different surface: the metadata is written out and read back
 * at runtime rather than inspected in place.</p>
 */
class ScalaDefinitionGenericsParitySpec extends AbstractScalaTypeElementSpec {

    void 'a bound type argument is not a type variable and an unbound one is'() {
        given:
        def context = buildContext('''
package test

import jakarta.inject.Singleton

trait Serializer[T]
trait Deserializer[T]
trait Serde[T] extends Serializer[T] with Deserializer[T]

@Singleton
class Test extends Serde[Object]

@Singleton
class ArrayListTest[E] extends Serde[java.util.ArrayList[E]]
''', [:], true)

        when: 'Serde[Object] binds T to a concrete type'
        def bound = getBeanDefinition(context, 'test.Test').getTypeArguments('test.Serde')

        then:
        bound.size() == 1
        bound[0].getType() == Object
        !bound[0].isTypeVariable()

        when: 'Serde[ArrayList[E]] binds T to a type that is itself parameterised by a variable'
        def nested = getBeanDefinition(context, 'test.ArrayListTest').getTypeArguments('test.Serde')

        then:
        nested.size() == 1
        !nested[0].isTypeVariable()
        nested[0].getTypeVariables()['E'].isTypeVariable()

        cleanup:
        context?.close()
    }

    void 'repeated annotations on a type argument reach the definition in order'() {
        given: 'the outermost annotation of a type is the last one written'
        def definition = buildBeanDefinition('test.Holder', '''
package test

import jakarta.inject.Singleton
import jakarta.validation.constraints.Size

@Singleton
class Holder(val values: java.util.List[String @Size(min = 1) @Size(max = 5)])
''')

        when:
        def argument = definition.getConstructor().getArguments()[0]
        def element = argument.getTypeParameters()[0]

        then:
        element.getType() == String
        element.getAnnotationMetadata().getAnnotationNames()
                .contains('jakarta.validation.constraints.Size$List')

        and: 'both survive, and in the order they were written'
        def sizes = element.getAnnotationMetadata()
                .getAnnotationValuesByName('jakarta.validation.constraints.Size')
        sizes*.getValues()*.collectEntries { k, v -> [k.toString(), v] } == [[min: 1], [max: 5]]
    }
}
