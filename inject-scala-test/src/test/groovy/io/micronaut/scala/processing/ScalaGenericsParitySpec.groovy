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
import io.micronaut.inject.ast.GenericPlaceholderElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

import java.util.function.Supplier

/**
 * Generics parity, ported from the Java module's {@code ClassElementSpec} -- "generic types
 * at type level", "array generic types at type level", "resolve generic type using
 * getTypeArguments", "getSuperType() with generic types" and "annotation metadata present on
 * deep type parameters".
 *
 * <p>These all passed as they stood. They are worth pinning because each rests on a
 * different part of the type model, and because the erasure a type variable reports is what
 * a generated bean definition binds against.</p>
 */
class ScalaGenericsParitySpec extends AbstractScalaTypeElementSpec {

    private static final String BOUNDED = '''
package test

class Foo

class Holder[T <: Foo] {
  def bounded(argument: T): T = null.asInstanceOf[T]
  def boundedArray(argument: Array[T]): Array[T] = null
  def unbounded[U](u: U): U = u
}
'''

    private Map methods(String source) {
        buildClassElement('test.Holder', source)
                .getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [it.name, it] }
    }

    void 'a bounded type variable is its bound'() {
        given:
        def bounded = methods(BOUNDED).bounded

        expect:
        bounded.getReturnType().getName() == 'test.Foo'
        bounded.getParameters()[0].getType().getName() == 'test.Foo'

        and: 'the generic view is still a placeholder'
        bounded.getGenericReturnType() instanceof GenericPlaceholderElement
    }

    void 'an array of a type variable keeps its dimensions'() {
        given:
        def boundedArray = methods(BOUNDED).boundedArray

        expect:
        boundedArray.getReturnType().getName() == 'test.Foo'
        boundedArray.getReturnType().isArray()
        boundedArray.getParameters()[0].getType().getName() == 'test.Foo'
        boundedArray.getParameters()[0].getType().isArray()
    }

    void 'an unbounded type variable is Object'() {
        given:
        def unbounded = methods(BOUNDED).unbounded

        expect:
        unbounded.getReturnType().getName() == 'java.lang.Object'
        unbounded.getParameters()[0].getType().getName() == 'java.lang.Object'
    }

    void 'the type argument of an implemented interface resolves'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

class Holder extends java.util.function.Supplier[String] {
  override def get(): String = "x"
}
''')

        expect:
        element.getTypeArguments(Supplier).get('T').getName() == 'java.lang.String'
    }

    void 'a generic superclass reports what it was bound to'() {
        given:
        def element = buildClassElement('test.Child', '''
package test

class Base[T](val item: T)

class Child extends Base[String]("x")
''')

        when:
        def superType = element.getSuperType().get()

        then:
        superType.getName() == 'test.Base'
        superType.getTypeArguments()['T'].getName() == 'java.lang.String'
    }

    void 'type-use annotations survive to the deepest type argument'() {
        given:
        def element = buildClassElement('test.Test', '''
package test

import jakarta.validation.constraints.*

class Test {
  var deepList: java.util.List[java.util.List[java.util.List[String @NotNull]] @NotEmpty] @Size = null
}
''')
        def type = element.getEnclosedElements(ElementQuery.ALL_FIELDS)
                .find { it.name == 'deepList' }
                .getGenericType()

        expect:
        names(type).contains('jakarta.validation.constraints.Size$List')

        and:
        def level1 = type.getTypeArguments()['E']
        names(level1).contains('jakarta.validation.constraints.NotEmpty$List')

        and: 'the level between carries none of its own, and must not inherit one'
        def level2 = level1.getTypeArguments()['E']
        !names(level2).any { it.startsWith('jakarta.validation.constraints') }

        and:
        def level3 = level2.getTypeArguments()['E']
        level3.getName() == 'java.lang.String'
        names(level3).contains('jakarta.validation.constraints.NotNull$List')
    }

    private static List<String> names(element) {
        element.getAnnotationMetadata().getAnnotationNames() as List
    }
}
