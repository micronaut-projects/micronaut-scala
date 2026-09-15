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

import io.micronaut.context.annotation.Executable
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaInheritedPropertyMutationVisitor

/**
 * The Scala counterpart of Core's {@code InheritedPropertyAnnotationMutationSpec} (Java and
 * Kotlin): a bean property is resolved for the type it is read through, so a visitor that
 * annotates a property of a super type must not annotate the same property read through a
 * subclass, and vice versa.
 */
class ScalaInheritedPropertyAnnotationMutationSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import scala.annotation.StaticAnnotation

class IgnoreProps(val value: Array[String]) extends StaticAnnotation

class Ignored extends StaticAnnotation

@IgnoreProps(Array("p2"))
class A extends B:
  var p1: String = null
  var p2: String = null

@IgnoreProps(Array("a2"))
class B extends C:
  var a1: String = null
  var a2: String = null

@IgnoreProps(Array("f2"))
class C:
  var f1: String = null
  var f2: String = null
'''

    void 'a property annotated through a super type is not annotated through the subclass'() {
        when:
        def recording = ScalaInheritedPropertyMutationVisitor.record {
            buildClassLoader('test.A', SOURCE)
        }

        then: 'each type only carries the annotations added through it'
        recording.ignoredProperties['test.C'] == ['f2']
        recording.ignoredProperties['test.B'] == ['a2']
        recording.ignoredProperties['test.A'] == ['p2']

        and: 'a field read through the type whose property was annotated carries the annotation'
        recording.ignoredFields['test.C'] == ['f2']
        recording.ignoredFields['test.B'] == ['a2']
        recording.ignoredFields['test.A'] == ['p2']
    }

    void 'a read member built from an accessor that declares annotations keeps the mutation made through its owner'() {
        given: 'a getter with an annotation of its own, so the read member is built from the accessor'
        def element = buildClassElement('test.A', '''
package test

import io.micronaut.context.annotation.Executable
import scala.annotation.StaticAnnotation

class Ignored extends StaticAnnotation

class A extends B

class B:
  @Executable
  def getF2(): String = null
  def setF2(f2: String): Unit = ()
''')

        when: 'the property is annotated through the subclass'
        def property = element.beanProperties.find { it.name == 'f2' }
        property.annotate('test.Ignored')

        then: 'the read member carries it, along with what the getter declares'
        property.readMember.get().hasAnnotation('test.Ignored')
        property.readMember.get().hasAnnotation(Executable)

        and: 'the getter read through the declaring type does not'
        !element.superType.get().beanProperties.find { it.name == 'f2' }.readMember.get().hasAnnotation('test.Ignored')
    }

    void 'the same holds for properties declared as class parameters'() {
        when:
        def recording = ScalaInheritedPropertyMutationVisitor.record {
            buildClassLoader('test.A', '''
package test

import scala.annotation.StaticAnnotation

class IgnoreProps(val value: Array[String]) extends StaticAnnotation

class Ignored extends StaticAnnotation

@IgnoreProps(Array("p2"))
class A(var p1: String, var p2: String) extends B(p1, p2)

@IgnoreProps(Array("a2"))
class B(var a1: String, var a2: String) extends C(a1, a2)

@IgnoreProps(Array("f2"))
class C(var f1: String, var f2: String)
''')
        }

        then:
        recording.ignoredProperties['test.C'] == ['f2']
        recording.ignoredProperties['test.B'] == ['a2']
        recording.ignoredProperties['test.A'] == ['p2']
    }
}
