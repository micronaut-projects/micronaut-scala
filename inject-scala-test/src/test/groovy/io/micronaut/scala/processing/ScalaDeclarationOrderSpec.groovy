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

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * dotty's {@code addAnnotation} prepends (`annot :: myAnnotations`), so
 * {@code denot.annotations} is in reverse declaration order. Everything read from it came
 * out backwards, which is invisible until order carries meaning -- repeated annotations,
 * the entries of a {@code @Repeatable} container built from them, and {@code @throws}
 * clauses.
 *
 * <p>Scala also has no notion of a repeatable container. javac collapses two
 * {@code @AliasFor} into one {@code @Aliases} before a processor sees them and core relies
 * on that, so repeats have to be collapsed here instead.</p>
 */
class ScalaDeclarationOrderSpec extends AbstractScalaTypeElementSpec {

    void 'repeated annotations keep the order they were written in'() {
        given:
        def element = buildClassElement('probe.Holder', '''
package probe

import io.micronaut.scala.processing.fixtures.Location

@Location("first")
@Location("second")
class Holder
''')

        expect:
        element.getAnnotationValuesByName('io.micronaut.scala.processing.fixtures.Location')
                *.stringValue()*.get() == ['first', 'second']
    }

    void 'thrown types keep the order they were written in'() {
        given:
        def element = buildClassElement('probe.Holder', '''
package probe

class Holder {
  @throws(classOf[java.sql.SQLException])
  @throws(classOf[io.micronaut.context.exceptions.BeanContextException])
  def two(): Unit = ()
}
''')

        when:
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'two' }

        then:
        method.getThrownTypes()*.getName() ==
                ['java.sql.SQLException', 'io.micronaut.context.exceptions.BeanContextException']
    }

    void 'every alias on an annotation member is applied, not just the first'() {
        given: 'two @AliasFor on one member, which javac would collapse into @Aliases'
        def definition = buildBeanDefinition('test.Test', '''
package test

import io.micronaut.context.annotation.AliasFor
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Named
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Bean
class MockBean(
  @(AliasFor @getter)(annotation = classOf[Replaces], member = "named")
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val named: String = ""
) extends StaticAnnotation

@MockBean(named = "foo")
class Test
''')

        expect: 'core reads the container first, so without one only the first alias applied'
        definition.stringValue('jakarta.inject.Named').get() == 'foo'
        definition.stringValue('io.micronaut.context.annotation.Replaces', 'named').get() == 'foo'
    }
}
