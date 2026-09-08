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
import io.micronaut.scala.processing.fixtures.ExternalInheritedSingleton
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import jakarta.inject.Named

/**
 * The hierarchy walk stopped at the edge of the compilation: a supertype read from the
 * classpath contributed no members at all, so a class extending a Java or
 * already-compiled Scala base inherited none of its injectable members. Three earlier
 * attempts failed on "Element of type [MethodElement$1] does not support adding
 * annotations at compilation time", which turned out to be this repository's own missing
 * {@code getMethodAnnotationMetadata()} override on the classpath method elements. The
 * message names the anonymous delegate Core's default returns, so it can never name the
 * element at fault.
 */
class ScalaClasspathInheritanceSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import io.micronaut.scala.processing.fixtures.ExternalBase
import jakarta.inject.Singleton

@Singleton
class Child extends ExternalBase {
  def childOwn(): String = "own"
}
'''

    private static final String ANNOTATED_SOURCE = '''
package test

import io.micronaut.scala.processing.fixtures.ExternalAnnotatedBase
import jakarta.inject.Singleton

@Singleton
class AnnotatedChild extends ExternalAnnotatedBase
'''

    void 'methods of a classpath supertype are inherited'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_METHODS)*.name

        then:
        names.contains('childOwn')

        and: 'every visibility, as the declared-member walk finds them'
        names.contains('basePublic')
        names.contains('baseProtected')
        names.contains('basePackagePrivate')
    }

    void 'fields of a classpath supertype are inherited'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_FIELDS)*.name

        then:
        names.contains('shared')
        names.contains('open')
        names.contains('secret')
    }

    void 'universal supertypes contribute nothing'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_METHODS)*.name

        then: 'the source path never produces these, so merging them would disagree with it'
        !names.contains('hashCode')
        !names.contains('productArity')
        !names.contains('canEqual')
    }

    void 'onlyDeclared does not reach the classpath supertype'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)

        when:
        def names = element.getEnclosedElements(ElementQuery.ALL_METHODS.onlyDeclared())*.name

        then:
        names.contains('childOwn')
        !names.contains('basePublic')
    }

    void 'an inherited classpath method can be annotated'() {
        given:
        def element = buildClassElement('test.Child', SOURCE)
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS).find { it.name == 'basePublic' }

        when: 'this is what a visitor does, and it used to throw'
        method.annotate('jakarta.inject.Inject')

        then:
        method.hasAnnotation('jakarta.inject.Inject')
    }

    void 'an inherited classpath method keeps its own metadata apart from the class\'s'() {
        given:
        def element = buildClassElement('test.AnnotatedChild', ANNOTATED_SOURCE)
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS).find { it.name == 'annotatedBaseMethod' }

        when: 'the method-only surface, which is what the override supplies'
        def methodMetadata = method.methodAnnotationMetadata.annotationMetadata

        then:
        methodMetadata.hasAnnotation(Named)
        !methodMetadata.hasAnnotation(ExternalInheritedSingleton)
    }

    void 'the declared metadata of an inherited classpath method is the declaration only'() {
        given:
        def element = buildClassElement('test.AnnotatedChild', ANNOTATED_SOURCE)
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS).find { it.name == 'annotatedBaseMethod' }

        when: 'this reads through getMethodAnnotationMetadata(), so the default answered with the combined metadata'
        def declared = method.declaredMethodAnnotationMetadata

        then:
        declared.hasAnnotation(Named)
        !declared.hasAnnotation(ExternalInheritedSingleton)

        when: 'and an annotation added by a visitor is part of the declaration'
        method.annotate('jakarta.inject.Inject')

        then:
        method.declaredMethodAnnotationMetadata.hasAnnotation('jakarta.inject.Inject')
    }
}
