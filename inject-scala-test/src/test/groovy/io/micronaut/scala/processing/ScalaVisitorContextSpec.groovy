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

import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor
import io.micronaut.scala.processing.visitor.ScalaProcessingClassLoader

class ScalaVisitorContextSpec extends AbstractScalaTypeElementSpec {

    void "visitor context resolves source classpath enum nested and missing classes"() {
        given:
        def lookedUp = [:]

        when:
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            lookedUp.string = context.getClassElement(String.name).orElse(null)
            lookedUp.root = context.getClassElement('vc.Root').orElse(null)
            lookedUp.color = context.getClassElement('vc.Color').orElse(null)
            lookedUp.nested = context.getClassElement('vc.Root$Nested').orElse(null)
            lookedUp.missing = context.getClassElement('vc.Missing').isPresent()
            lookedUp.packageElements = context.getClassElements('vc')*.name as Set
        }, {
            buildClassLoader('vc.Root', '''
package vc

import jakarta.inject.Singleton

@Singleton
class Root:
  class Nested

class Other

enum Color:
  case Red, Blue
''')
        })

        then:
        lookedUp.string.name == String.name
        lookedUp.root.name == 'vc.Root'
        lookedUp.root.hasStereotype('jakarta.inject.Singleton')
        lookedUp.color.enum
        lookedUp.color.values() == ['Red', 'Blue']
        lookedUp.nested.name == 'vc.Root$Nested'
        lookedUp.nested.inner
        !lookedUp.missing
        lookedUp.packageElements.containsAll(['vc.Root', 'vc.Other', 'vc.Color'])
    }

    void "a visitor runs with the compilation classpath ahead of the plugin's parent chain"() {
        given:
        def seen = [:]

        when:
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            def loader = Thread.currentThread().contextClassLoader
            seen.loader = loader
            // On the test classpath, and so visible to the plugin's parent as well; a visitor
            // must get the compilation's copy, not whatever the compiler was loaded with.
            seen.dependency = loader.loadClass('jakarta.validation.constraints.Digits').classLoader
            // What the plugin bundles is the plugin's, so the two sides agree on it.
            seen.bundled = loader.loadClass('org.objectweb.asm.ClassWriter').classLoader
            seen.visitorApi = loader.loadClass(TypeElementVisitor.name)
        }, {
            buildClassLoader('vc.Bean', '''
package vc

import jakarta.inject.Singleton

@Singleton
class Bean
''')
        })

        then:
        // The plugin's own copy of the class, so by name: the test sees the one on its classpath.
        seen.loader.getClass().name == ScalaProcessingClassLoader.name
        seen.dependency.is(seen.loader)
        seen.bundled.is(seen.loader.parent)
        seen.visitorApi.is(TypeElementVisitor)
    }
}
