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
 * Ported from the Kotlin module's {@code ClassElementSpec} "how the annotations from the
 * type are propagated". A type variable is erased to its bound, and the annotations that
 * reach a bean definition are the ones on the type that is actually there.
 *
 * <p>Only the type parameter's own annotations were read, so making a parameter generic
 * silently dropped them: {@code save(book: MyBook)} carried {@code MyBook}'s
 * {@code @Introspected} and {@code save[T <: MyBook](book: T)} carried nothing, though both
 * compile to the same JVM signature.</p>
 */
class ScalaPlaceholderAnnotationSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton

@Introspected
class MyBook

@Singleton
class MyBean {
  @Executable def save(book: MyBook): Unit = ()
  @Executable def saveGeneric[T <: MyBook](book: T): Unit = ()
  @Executable def saveAll(books: java.util.List[MyBook]): Unit = ()
  @Executable def saveAllGeneric[T <: MyBook](books: java.util.List[T]): Unit = ()
  @Executable def unbounded[T](item: T): Unit = ()
}
'''

    private Map methods() {
        buildClassElement('test.MyBean', SOURCE)
                .getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [it.name, it] }
    }

    private static List<String> names(element) {
        element.getAnnotationMetadata().getAnnotationNames() as List
    }

    void 'a generic parameter carries the annotations of its bound'() {
        given:
        def m = methods()

        when:
        def concrete = m.save.getParameters()[0].getGenericType()
        def generic = m.saveGeneric.getParameters()[0].getGenericType()

        then: 'both compile to the same JVM parameter type'
        concrete.getName() == 'test.MyBook'
        generic.getName() == 'test.MyBook'

        and: 'so both have to answer the same about it'
        names(concrete).contains('io.micronaut.core.annotation.Introspected')
        names(generic).contains('io.micronaut.core.annotation.Introspected')
    }

    void 'a generic type argument carries the annotations of its bound'() {
        given:
        def m = methods()

        when:
        def concrete = m.saveAll.getParameters()[0].getGenericType().getTypeArguments()['E']
        def generic = m.saveAllGeneric.getParameters()[0].getGenericType().getTypeArguments()['E']

        then:
        concrete.getName() == 'test.MyBook'
        generic.getName() == 'test.MyBook'

        and:
        names(concrete).contains('io.micronaut.core.annotation.Introspected')
        names(generic).contains('io.micronaut.core.annotation.Introspected')
    }

    void 'an unbounded type variable picks up nothing'() {
        given:
        def m = methods()

        when: 'the bound is Object, which contributes nothing to merge'
        def unbounded = m.unbounded.getParameters()[0].getGenericType()

        then:
        unbounded.getName() == 'java.lang.Object'
        !names(unbounded).contains('io.micronaut.core.annotation.Introspected')
    }
}
