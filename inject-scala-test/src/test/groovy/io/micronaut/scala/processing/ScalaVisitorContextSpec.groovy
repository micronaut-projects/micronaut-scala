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
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

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
}
