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
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * Annotations read from a class file used to go straight into a
 * {@code MutableAnnotationMetadata}, which walks no meta-annotations. The same annotation
 * therefore answered differently depending on whether the type came from source or from
 * the classpath: no stereotypes, no {@code @AliasFor}, no mappers, no repeatable
 * unwrapping.
 */
class ScalaClasspathMetadataSpec extends AbstractScalaTypeElementSpec {

    private static final String FIXTURES = 'io.micronaut.scala.processing.fixtures.'

    private Map inspect() {
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            def classpath = context.getClassElement(FIXTURES + 'ExternalMachine').orElse(null)
            if (classpath != null) {
                found.classpathScope = classpath.hasStereotype(AnnotationUtil.SCOPE)
                found.classpathSingleton = classpath.hasStereotype('jakarta.inject.Singleton')
            }
            def source = context.getClassElement('probe.Local').orElse(null)
            if (source != null) {
                found.sourceScope = source.hasStereotype(AnnotationUtil.SCOPE)
                found.sourceSingleton = source.hasStereotype('jakarta.inject.Singleton')
            }
        }, {
            buildClassLoader('probe.Local', '''
package probe

import io.micronaut.scala.processing.fixtures.ExternalInheritedSingleton

@ExternalInheritedSingleton
class Local
''')
        })
        found
    }

    void 'a classpath type resolves the stereotypes of its annotations'() {
        when: 'the same annotation is read from a class file rather than from source'
        def found = inspect()

        then: '@ExternalInheritedSingleton is meta-annotated @Singleton, which is a scope'
        found.classpathScope
        found.classpathSingleton
    }

    void 'a classpath type and a source type agree about the same annotation'() {
        when:
        def found = inspect()

        then: 'the two element kinds must not disagree about what an annotation means'
        found.classpathScope == found.sourceScope
        found.classpathSingleton == found.sourceSingleton
    }
}
