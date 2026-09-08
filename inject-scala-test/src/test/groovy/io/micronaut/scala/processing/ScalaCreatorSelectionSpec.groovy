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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity: creator selection, which {@code DISABLED_TESTS.md} lists for
 * {@code ScalaBeanIntrospectionSpec} and which nothing covered.
 *
 * <p>Scala's primary constructor comes first, but being first does not make it the one
 * Micronaut should call. Core's own default prefers a constructor annotated {@code @Inject}
 * or {@code @Creator}; the override here took the first unconditionally, so the annotation
 * was read into the model and then ignored.</p>
 */
class ScalaCreatorSelectionSpec extends AbstractScalaTypeElementSpec {

    private static final String ANNOTATED_SECONDARY = '''
package test

import io.micronaut.core.annotation.Creator
import io.micronaut.core.annotation.Introspected

@Introspected
class Widget(val name: String, val size: Int) {
  @Creator def this(name: String) = this(name, 0)
}
'''

    void 'an annotated secondary constructor is the primary constructor'() {
        given:
        def element = buildClassElement('test.Widget', ANNOTATED_SECONDARY)

        expect: 'both are modelled, and the annotation is on the secondary'
        element.getEnclosedElements(ElementQuery.CONSTRUCTORS).size() == 2

        and:
        element.getPrimaryConstructor().get().getParameters()*.getName() == ['name']
    }

    void 'the introspection constructs through the annotated constructor'() {
        given:
        def introspection = buildBeanIntrospection('test.Widget', ANNOTATED_SECONDARY)

        expect:
        introspection.getConstructorArguments()*.getName() == ['name']

        and: 'and it really is usable, not just described'
        introspection.instantiate('hello').name() == 'hello'
    }

    void 'Inject on a secondary constructor selects it too'() {
        given:
        def element = buildClassElement('test.Widget', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Inject

@Introspected
class Widget(val name: String, val size: Int) {
  @Inject def this(name: String) = this(name, 0)
}
''')

        expect:
        element.getPrimaryConstructor().get().getParameters()*.getName() == ['name']
    }

    void 'with no annotation the primary constructor still wins'() {
        given:
        def element = buildClassElement('test.Widget', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Widget(val name: String, val size: Int) {
  def this(name: String) = this(name, 0)
}
''')

        expect: 'Scala declares the primary first, and nothing overrides that'
        element.getPrimaryConstructor().get().getParameters()*.getName() == ['name', 'size']
    }

    void 'a companion object factory is the creator'() {
        given: 'the idiomatic Scala factory, which the backend emits a static forwarder for'
        def source = '''
package test

import io.micronaut.core.annotation.Creator
import io.micronaut.core.annotation.Introspected

@Introspected
class Widget(val name: String, val size: Int)

object Widget {
  @Creator def of(name: String): Widget = new Widget(name, 0)
  def notACreator(x: String): Widget = new Widget(x, 1)
}
'''
        def element = buildClassElement('test.Widget', source)

        expect: 'it is modelled as a static method of the class, as the forwarder will be'
        element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .findAll { it.isStatic() }*.name == ['of']

        and: 'and only the annotated one counts as a creator'
        element.getAccessibleStaticCreators()*.name == ['of']
        element.getPrimaryConstructor().get().getName() == 'of'

        when:
        def introspection = buildBeanIntrospection('test.Widget', source)

        then:
        introspection.getConstructorArguments()*.getName() == ['name']

        and: 'and the call really links against the forwarder at runtime'
        introspection.instantiate('hello').name() == 'hello'
    }

    void 'a companion the compiler emits no forwarder for contributes nothing'() {
        when: 'an object nested in a class is not static, so no forwarder is emitted'
        def outer = buildClassElement('test.Outer', '''
package test

import io.micronaut.core.annotation.Creator

class Outer {
  class InnerC(val n: String)
  object InnerC { @Creator def of(n: String): InnerC = new InnerC(n) }
}
''')

        then: 'predicting one would generate a call to a method that does not exist'
        outer.getEnclosedElements(ElementQuery.of(ClassElement))
                .first()
                .getAccessibleStaticCreators()
                .isEmpty()

        when: 'a name shared with a member of the class also suppresses the forwarder'
        def clash = buildClassElement('test.Clash', '''
package test

import io.micronaut.core.annotation.Creator

class Clash(val name: String)
object Clash { @Creator def name(x: String): Clash = new Clash(x) }
''')

        then:
        clash.getAccessibleStaticCreators().isEmpty()
    }

    void 'an annotated trait is not a bean'() {
        expect: 'as in the Java module, an interface carrying a bean annotation defines none'
        buildBeanDefinition('test.Api', '''
package test

import io.micronaut.http.annotation.Controller

@Controller
trait Api
''') == null

        and:
        buildBeanDefinition('test.Api2', '''
package test

import jakarta.inject.Singleton

@Singleton
trait Api2
''') == null
    }
}
