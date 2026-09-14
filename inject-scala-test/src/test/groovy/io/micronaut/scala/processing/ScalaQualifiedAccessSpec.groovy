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

import io.micronaut.inject.ast.ElementModifier
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

import java.lang.reflect.Modifier

/**
 * The Element API's access modifiers decide whether Micronaut will inject, introspect or
 * directly access a member, so they have to describe the *emitted* member. Scala's
 * `protected` means subclass-only, which the JVM cannot express, so scalac emits both
 * `protected` and `protected[pkg]` as public -- reporting them as protected made Micronaut
 * refuse members it could legally call.
 */
class ScalaQualifiedAccessSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import jakarta.inject.Singleton

@Singleton
class Target {
  def openMethod(): Int = 1
  private def privateMethod(): Int = 2
  protected def protectedMethod(): Int = 3
  private[test] def qualifiedPrivateMethod(): Int = 4
  protected[test] def qualifiedProtectedMethod(): Int = 5
}
'''

    void 'reported access matches the emitted bytecode'() {
        given:
        def element = buildClassElement('test.Target', SOURCE)
        def loader = buildClassLoader('test.Target', SOURCE)
        def emitted = loader.loadClass('test.Target').getDeclaredMethods()
                .collectEntries { [(it.getName()): it.getModifiers()] }
        def declared = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [(it.getName()): it] }

        expect: 'every declared method is modelled exactly as the JVM sees it'
        ['openMethod', 'privateMethod', 'protectedMethod',
         'qualifiedPrivateMethod', 'qualifiedProtectedMethod'].every { name ->
            def method = declared[name]
            def modifiers = emitted[name]
            method != null && modifiers != null &&
                    method.isPublic() == Modifier.isPublic(modifiers) &&
                    method.isPrivate() == Modifier.isPrivate(modifiers) &&
                    method.isProtected() == Modifier.isProtected(modifiers)
        }
    }

    void 'Scala protected is public because that is what is emitted'() {
        given:
        def element = buildClassElement('test.Target', SOURCE)
        def declared = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())
                .collectEntries { [(it.getName()): it] }

        expect:
        declared['protectedMethod'].isPublic()
        !declared['protectedMethod'].isProtected()
        declared['qualifiedProtectedMethod'].isPublic()
        !declared['qualifiedProtectedMethod'].isProtected()

        and: 'a plain private member is genuinely private, and a qualified one is not'
        declared['privateMethod'].isPrivate()
        declared['qualifiedPrivateMethod'].isPublic()
    }

    void 'a sealed class reports the sealed modifier'() {
        given:
        def element = buildClassElement('test.Shape', '''
package test

sealed abstract class Shape
''')

        expect:
        element.getModifiers().contains(ElementModifier.SEALED)
    }
}
