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
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Ported from the Kotlin module's {@code ClassElementSpec} "recursive generic method return"
 * family, which walks from a method to its return type and on into that type's members.
 *
 * <p>A class element is built either from a declaration or from a *reference* to a type -- a
 * return type, a parameter type, a field type, a type argument. A reference carries no
 * declaration, and every member query answered empty for one, so that walk stopped at the
 * first step. Core makes exactly that walk when resolving introduction, validation and AOP
 * targets.</p>
 */
class ScalaTypeReferenceMembersSpec extends AbstractScalaTypeElementSpec {

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a method return type answers member queries'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

trait Api {
  def op(): String
  def other(): Int
}

class Holder {
  def api(): Api = null
}
''')

        when:
        def returnType = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'api' }
                .getReturnType()

        then:
        returnType.getName() == 'test.Api'
        returnType.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())*.name.sort() == ['op', 'other']
        returnType.findMethod('op').get().getReturnType().getName() == 'java.lang.String'
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a classpath return type answers member queries'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

class Holder {
  def stream(): java.util.stream.Stream[String] = null
}
''')

        when:
        def returnType = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'stream' }
                .getReturnType()

        then:
        returnType.getName() == 'java.util.stream.Stream'
        returnType.getTypeArguments()['T'].getName() == 'java.lang.String'

        and: 'and the walk continues into it, which is how core resolves an advice target'
        returnType.findMethod('filter').get().getReturnType().getName() == 'java.util.stream.Stream'
    }

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a self-referential return type is walkable'() {
        given:
        def element = buildClassElement('test.Holder', '''
package test

trait Builder[T <: Builder[T]] {
  def self(): T
}

class Holder {
  def builder(): Builder[?] = null
}
''')

        when:
        def returnType = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .find { it.name == 'builder' }
                .getReturnType()

        then:
        returnType.getName() == 'test.Builder'
        returnType.findMethod('self').get().getReturnType().getName() == 'test.Builder'
    }

    void 'a classpath type variable erases to its bound'() {
        when:
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            found.enumArg = context.getClassElement('java.lang.Enum')
                    .map { it.getTypeArguments()['E'].getName() }.orElse(null)
            found.streamArgs = context.getClassElement('java.util.stream.BaseStream')
                    .map { it.getTypeArguments().collectEntries { k, v -> [k, v.getName()] } }.orElse(null)
            found.enumNested = context.getClassElement('java.lang.Enum')
                    .map { it.getTypeArguments()['E'].getTypeArguments().collectEntries { k, v -> [k, v.getName()] } }
                    .orElse(null)
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })

        then: 'java.lang.Enum<E extends Enum<E>> erases E to Enum, not to Object'
        found.enumArg == 'java.lang.Enum'

        and: 'BaseStream<T, S extends BaseStream<T, S>> erases each on its own bound'
        found.streamArgs == [T: 'java.lang.Object', S: 'java.util.stream.BaseStream']

        and: '''and the bound's own arguments are modelled one level further, as the source
                path models `Direct[T <: Comparable[T]]` in ScalaRecursiveGenericsSpec. The
                bound is parameterized by the variable itself, so modelling it literally does not
                terminate; reporting no arguments at all left a caller unable to tell a
                parameterized bound from a raw one, and a classpath type and a source type have
                to stop at the same depth, since they are now built by the same code'''
        found.enumNested == [E: 'java.lang.Enum']
    }
}
