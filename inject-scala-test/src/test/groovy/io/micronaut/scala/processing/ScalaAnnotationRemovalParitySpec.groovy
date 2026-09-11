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

import io.micronaut.context.annotation.Prototype
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotationRemovalVisitor
import io.micronaut.scala.processing.test.ScalaAnnotationRemovalVisitor.Mode

import java.util.function.Supplier

/**
 * P0 parity, ported from {@code inject-java}'s {@code RemoveAnnotationSpec}.
 *
 * <p>Visitor mutation was covered here only in the direction that adds. Adding is forgiving:
 * an annotation written twice is the same as written once, so a mutation reaching a copy of the
 * metadata rather than the metadata still looks right. Removal has no such slack -- one that
 * misses leaves the bean exactly as it was, and the test that would notice is the one asserting
 * the annotation is gone.</p>
 *
 * <p>Scala reaches this through a different door. Annotations arrive from the plugin's own model
 * rather than from {@code javax.lang.model} mirrors, and this branch has already had to correct
 * their order twice and where their members are read from, so where a removal lands is worth
 * asserting rather than assuming.</p>
 */
class ScalaAnnotationRemovalParitySpec extends AbstractScalaTypeElementSpec {

    private static final String SCOPE_ONE = 'removeann.ScopeOne'
    private static final String SCOPE_TWO = 'removeann.ScopeTwo'

    // The Java spec removes fixtures from inject-java's own test sources, which are not
    // published; the equivalent scopes are declared in the compiled source instead.
    private static final String SCOPES = '''
@Retention(RetentionPolicy.RUNTIME)
@jakarta.inject.Scope
class ScopeOne extends StaticAnnotation

// As in the Java fixture, ScopeTwo is a scope in its own right as well as carrying ScopeOne.
// That is what makes removing the ScopeOne stereotype observable: without its own @Scope the
// class would simply stop being scoped, and the test could not tell a targeted removal from a
// blunt one.
@Retention(RetentionPolicy.RUNTIME)
@ScopeOne
@jakarta.inject.Scope
class ScopeTwo extends StaticAnnotation
'''

    private static String source(String body) {
        '''
package removeann

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Requires
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation
''' + body
    }

    private def build(Mode mode, String annotationName, String replacement = null, String body) {
        ScalaAnnotationRemovalVisitor.removing(mode, annotationName, replacement, {
            buildBeanDefinition('removeann.Test', source(body))
        } as Supplier)
    }

    void "removes an annotation a visitor asks to remove"() {
        when:
        def definition = build(Mode.REMOVE, SCOPE_ONE, '''
@ScopeOne
@Bean
class Test
''' + SCOPES)

        then: 'the annotation and the scope it carried are both gone'
        definition != null
        !definition.hasDeclaredAnnotation(SCOPE_ONE)
        !definition.hasStereotype(AnnotationUtil.SCOPE)
        definition.getAnnotationNamesByStereotype(AnnotationUtil.SCOPE).size() == 0
    }

    void "removes an annotation matched by a predicate"() {
        when:
        def definition = build(Mode.REMOVE_IF, SCOPE_ONE, '''
@ScopeOne
@Bean
class Test
''' + SCOPES)

        then:
        definition != null
        !definition.hasDeclaredAnnotation(SCOPE_ONE)
        definition.getAnnotationNamesByStereotype(AnnotationUtil.SCOPE).size() == 0
    }

    void "removes only the named stereotype, leaving the annotation that carried it"() {
        when: 'ScopeTwo is meta-annotated with ScopeOne, which is itself a scope'
        def definition = build(Mode.REMOVE_STEREOTYPE, SCOPE_ONE, '''
@ScopeTwo
@Bean
class Test
''' + SCOPES)

        then: 'the intermediate stereotype is gone'
        definition != null
        !definition.hasStereotype(SCOPE_ONE)
        !definition.hasDeclaredStereotype(SCOPE_ONE)

        and: 'but the annotation that carried it still supplies the scope'
        definition.getAnnotationNamesByStereotype(AnnotationUtil.SCOPE) as Set == [SCOPE_TWO] as Set
    }

    void "replaces an annotation when a visitor removes and adds"() {
        when:
        def definition = build(Mode.REPLACE, SCOPE_ONE, Prototype.name, '''
@ScopeOne
@Bean
class Test
''' + SCOPES)

        then: 'the replacement is declared and the original is not'
        definition != null
        definition.hasDeclaredAnnotation(Prototype)
        !definition.hasDeclaredAnnotation(SCOPE_ONE)

        and: 'exactly one scope survives, and it is the new one'
        definition.hasStereotype(AnnotationUtil.SCOPE)
        definition.getAnnotationNamesByStereotype(AnnotationUtil.SCOPE) as Set == [Prototype.name] as Set
    }

    void "removing a repeatable annotation removes every occurrence and the container"() {
        when: 'two occurrences of a repeatable annotation'
        def definition = build(Mode.REMOVE, Requires.name, '''
@Requires(env = Array("test"))
@Requires(property = "feature.enabled", value = "true")
@Bean
class Test
''')

        then: 'removing the repeatable type takes both entries and the container with them'
        definition != null
        !definition.hasDeclaredAnnotation(Requires)
        !definition.hasDeclaredAnnotation('io.micronaut.context.annotation.Requirements')
        definition.getAnnotationValuesByType(Requires).size() == 0
    }
}
