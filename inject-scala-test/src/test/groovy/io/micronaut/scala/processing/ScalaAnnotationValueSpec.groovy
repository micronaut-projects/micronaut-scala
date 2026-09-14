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

import io.micronaut.context.annotation.Property
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler

/**
 * Annotation values must be read from the typed tree, never from its printed form.
 * Two symmetrical defects came from doing the latter: any argument the extractor did not
 * understand was stored as the compiler's display string, and any {@code String} whose
 * *content* mentioned {@code classOf} was turned into a class reference.
 */
class ScalaAnnotationValueSpec extends AbstractScalaTypeElementSpec {

    void 'a string whose content mentions classOf stays a string'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import io.micronaut.context.annotation.Property
import jakarta.inject.Singleton

@Singleton
@Property(name = "tag", value = "classOf[Int] is the tag")
class Target
''')

        when:
        def value = element.getAnnotation(Property).getValue(String).get()

        then: 'the text is preserved verbatim rather than becoming a class reference'
        value == 'classOf[Int] is the tag'
        !(value instanceof AnnotationClassValue)
    }

    void 'a genuine class literal is still read as a class value'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(beans = Array(classOf[java.lang.Runnable]))
class Target
''')

        when:
        def beans = element.getAnnotation(Requires).getValues().get('beans')

        then:
        beans[0] instanceof AnnotationClassValue
        beans[0].name == 'java.lang.Runnable'
    }

    void 'a non-constant annotation argument is reported instead of stored as printed text'() {
        when: 'a plain val is used as an annotation argument, which is not a constant in Scala 3'
        def result = ScalaCompiler.buildAndGetDiagnostics('test.Target', '''
package test

import io.micronaut.context.annotation.Property
import jakarta.inject.Singleton

object Config {
  val TAG = "not-a-constant"
}

@Singleton
@Property(name = "tag", value = Config.TAG)
class Target
''')

        then: 'the compilation reports it, at the argument position'
        def error = result.errors().find { it.contains('Unsupported Scala annotation value') }
        error != null
        error.startsWith('Target.scala:12:')
        error.contains('final val')
    }

    void 'a final val annotation argument is a constant and is read'() {
        given:
        def element = buildClassElement('test.Target', '''
package test

import io.micronaut.context.annotation.Property
import jakarta.inject.Singleton

object Config {
  final val TAG = "a-constant"
}

@Singleton
@Property(name = "tag", value = Config.TAG)
class Target
''')

        expect:
        element.getAnnotation(Property).getValue(String).get() == 'a-constant'
    }
}
