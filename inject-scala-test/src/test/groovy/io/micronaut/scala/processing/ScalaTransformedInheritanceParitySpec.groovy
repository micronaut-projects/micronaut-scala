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
import io.micronaut.scala.processing.test.annotation.ScalaInheritableSource
import io.micronaut.scala.processing.test.annotation.ScalaMappedResult

/**
 * P0 parity, ported from {@code inject-java}'s {@code TransformToInheritedAnnotationSpec} and
 * {@code TransformNotInheritedAnnotationSpec}.
 *
 * <p>An annotation transformer runs while metadata is being built, and what it produces then has
 * to take part in everything downstream -- including inheritance, which is decided by the
 * stereotypes of the <em>result</em> rather than of what the author wrote. Transformer coverage
 * here checks that a transform happens at all, on a type, in one place. It does not check that
 * the result then behaves like an annotation.</p>
 *
 * <p>Scala makes the interesting half the ordinary one. The declaration that carries the
 * transform is a trait member, so the result has to survive being merged into the implementing
 * class's method and reach the written definition -- through the interface edge that
 * {@code ScalaAnnotationInheritanceParitySpec} established Micronaut treats like any other.</p>
 */
class ScalaTransformedInheritanceParitySpec extends AbstractScalaTypeElementSpec {

    void "carries a transformed annotation from a trait method to the implementation"() {
        when:
        def definition = buildBeanDefinition('transforminherit.Service', '''
package transforminherit

import io.micronaut.context.annotation.Executable
import io.micronaut.scala.processing.test.annotation.ScalaInheritableSource
import jakarta.inject.Singleton

trait Operations:
  @ScalaInheritableSource
  @Executable
  def helloWorld(): String

@Singleton
class Service extends Operations:
  override def helloWorld(): String = "Hello world"
''')
        def method = definition.getRequiredMethod('helloWorld')

        then: 'the source annotation is gone, replaced by what the transformer produced'
        !method.hasAnnotation(ScalaInheritableSource)
        method.hasAnnotation(ScalaMappedResult)
        method.stringValue(ScalaMappedResult).get() == 'inheritable'
    }

    void "carries a transformed annotation from a trait to the implementing class"() {
        when: 'the same transform written on the type rather than the member'
        def definition = buildBeanDefinition('transforminherit.Service', '''
package transforminherit

import io.micronaut.scala.processing.test.annotation.ScalaInheritableSource
import jakarta.inject.Singleton

@ScalaInheritableSource
trait Marked

@Singleton
class Service extends Marked
''')

        then: '''the result carries @Inherited as a stereotype, which is what decides the crossing
                 -- the author never wrote it, the transformer added it'''
        definition.hasAnnotation(ScalaMappedResult)
        definition.stringValue(ScalaMappedResult).get() == 'inheritable'
        !definition.hasDeclaredAnnotation(ScalaMappedResult)
    }

    void "still transforms when nothing is inherited"() {
        when: 'written directly on the class, so no edge is crossed at all'
        def definition = buildBeanDefinition('transforminherit.Service', '''
package transforminherit

import io.micronaut.scala.processing.test.annotation.ScalaInheritableSource
import jakarta.inject.Singleton

@ScalaInheritableSource
@Singleton
class Service
''')

        then: 'the transform is what produced it, and here it is declared rather than inherited'
        definition.hasDeclaredAnnotation(ScalaMappedResult)
        definition.stringValue(ScalaMappedResult).get() == 'inheritable'
    }
}
