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

import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * An annotation type that declares no {@code @Retention} was treated as {@code RUNTIME},
 * so a Java annotation the JLS says is {@code CLASS} was written into the bean definition
 * and shipped at runtime.
 *
 * <p>The JLS default cannot be applied to every annotation, though: a Scala annotation is a
 * class extending {@code StaticAnnotation}, not a Java annotation type, and has no JLS
 * retention at all. It exists as compile-time metadata that Micronaut bakes into the
 * generated definition, so treating it as {@code CLASS} makes it vanish.
 */
class ScalaRetentionSpec extends AbstractScalaTypeElementSpec {

    void 'a Java annotation declaring no retention is CLASS and is not written at runtime'() {
        given:
        def definition = buildBeanDefinition('probe.Target', '''
package probe

import io.micronaut.scala.processing.fixtures.ExternalUnretained
import jakarta.inject.Singleton

@Singleton
@ExternalUnretained
class Target
''')

        expect:
        !definition.getAnnotationMetadata()
                .hasAnnotation('io.micronaut.scala.processing.fixtures.ExternalUnretained')

        and: 'a runtime-retained annotation on the same class is still written'
        definition.getAnnotationMetadata().hasAnnotation('jakarta.inject.Singleton')
    }

    void 'a Java annotation declaring RUNTIME retention is written at runtime'() {
        given:
        def definition = buildBeanDefinition('probe.Target', '''
package probe

import io.micronaut.scala.processing.fixtures.ExternalDefaulted
import jakarta.inject.Singleton

@Singleton
@ExternalDefaulted
class Target
''')

        expect:
        definition.getAnnotationMetadata()
                .hasAnnotation('io.micronaut.scala.processing.fixtures.ExternalDefaulted')
    }

    void 'compiler-internal annotations are not part of the model'() {
        given:
        def definition = buildBeanDefinition('probe.Target', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Target
''')
        def element = buildClassElement('probe.Target', '''
package probe

import jakarta.inject.Singleton

@Singleton
class Target
''')

        expect: 'dotty attaches SourceFile to every class it compiles; that is its bookkeeping, not the user model'
        definition.getAnnotationMetadata().getAnnotationNames().every {
            !it.startsWith('scala.annotation.internal.')
        }
        element.getAnnotationNames().every { !it.startsWith('scala.annotation.internal.') }

        and: 'the user annotation is still there'
        definition.getAnnotationMetadata().hasAnnotation('jakarta.inject.Singleton')
    }

    void 'a Scala annotation declaring no retention still reaches the runtime'() {
        given:
        def definition = buildBeanDefinition('probe.Target', '''
package probe

import jakarta.inject.Qualifier
import scala.annotation.StaticAnnotation

@Qualifier
class MyQualifier extends StaticAnnotation

@MyQualifier
class Target
''')

        expect: 'a Scala annotation has no JLS retention, so the JLS default must not apply'
        definition.getAnnotationMetadata()
                .getAnnotationNameByStereotype(AnnotationUtil.QUALIFIER).get() == 'probe.MyQualifier'
    }
}
