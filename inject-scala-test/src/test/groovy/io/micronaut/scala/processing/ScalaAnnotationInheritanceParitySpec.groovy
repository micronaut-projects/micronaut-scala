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

/**
 * P0 parity, ported from {@code inject-java}'s {@code AnnotationInheritanceSpec}.
 *
 * <p>Whether an annotation crosses an inheritance edge is decided by {@code @Inherited}, and
 * getting it wrong is silent in both directions: a scope that should not be inherited makes a
 * subclass a bean it was never meant to be, and one that should be is quietly dropped. Nothing
 * here asserted the distinction -- inheritance was tested for members, not for the annotations
 * on the type itself.</p>
 *
 * <p>Scala raises a question Java cannot. {@code @Inherited} is defined for <em>class</em>
 * inheritance only; implementing an interface never carries annotations across, however the
 * annotation is declared. A Scala trait is an interface, and mixing one in is the ordinary way
 * to share behaviour, so the same annotation on the same supertype has to be inherited or not
 * depending on whether that supertype is a class or a trait.</p>
 */
class ScalaAnnotationInheritanceParitySpec extends AbstractScalaTypeElementSpec {

    private static final String MARKERS = '''
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@jakarta.inject.Qualifier
class MyQ extends StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@jakarta.inject.Scope
class MyS extends StaticAnnotation
'''

    private static String source(String body) {
        '''
package anntest

import io.micronaut.context.annotation.Prototype
import io.micronaut.context.annotation.Requires
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.lang.annotation.Inherited
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation
''' + body
    }

    void "does not inherit a scope or qualifier that is not declared inherited"() {
        when: 'the parent carries a scope, a qualifier and a requirement, none of them @Inherited'
        def definition = buildBeanDefinition('anntest.Test', source('''
@Prototype
class Test extends Parent

@Singleton
@Named("test")
@Requires(property = "foo.bar")
class Parent
'''))

        then: 'only the annotation declared here is declared'
        definition.hasDeclaredAnnotation(Prototype)
        definition.declaredAnnotationNames == [Prototype.name] as Set
        definition.hasDeclaredStereotype(AnnotationUtil.SCOPE)

        and: 'the qualifier does not cross'
        !definition.hasAnnotation(AnnotationUtil.NAMED)
        !definition.hasAnnotation(AnnotationUtil.QUALIFIER)
        definition.declaredQualifier == null

        and: 'the scope resolved is the one declared here, not the inherited singleton'
        definition.scopeName.get() == Prototype.name
        !definition.isSingleton()

        and: 'and the requirement does not cross either'
        definition.getDeclaredAnnotationValuesByType(Requires).size() == 0
    }

    void "does not make a subclass a bean by inheriting the parent's scope"() {
        when: 'the subclass declares nothing of its own'
        def definition = buildBeanDefinition('anntest.Test', source('''
class Test extends Parent

@Singleton
@Named("test")
@Requires(property = "foo.bar")
class Parent
'''))

        then: 'nothing was inherited, so there is no bean at all'
        definition == null
    }

    void "inherits a scope and qualifier declared inherited through a superclass"() {
        when:
        def definition = buildBeanDefinition('anntest.Test', source('''
class Test extends Parent

@MyQ
@MyS
@Requires(property = "foo.bar")
class Parent
''' + MARKERS))

        then: 'present, but not as declared annotations of the subclass'
        definition.hasAnnotation('anntest.MyQ')
        definition.hasAnnotation('anntest.MyS')
        !definition.hasDeclaredAnnotation('anntest.MyQ')
        !definition.hasDeclaredAnnotation('anntest.MyS')

        and: 'their stereotypes come with them, as stereotypes rather than annotations'
        definition.hasStereotype(AnnotationUtil.SCOPE)
        definition.hasStereotype(AnnotationUtil.QUALIFIER)
        !definition.hasAnnotation(AnnotationUtil.SCOPE)
        !definition.hasAnnotation(AnnotationUtil.QUALIFIER)

        and: 'and both resolve'
        definition.declaredQualifier.toString() == '@MyQ'
        definition.scopeName.get() == 'anntest.MyS'

        and: 'a requirement is never inherited, @Inherited or not'
        definition.getAnnotationValuesByType(Requires).size() == 0
    }

    void "inherits through a trait exactly as through a superclass"() {
        when: '''the same annotations on the same supertype, mixed in rather than extended.
                 The JVM defines @Inherited for class inheritance only, but Micronaut does not
                 use the JVM rule: its own hierarchy walk visits interfaces before superclasses
                 (NativeElementsHelper.populateTypeHierarchy), so an interface is an inheritance
                 edge like any other. A trait must therefore behave as the class did'''
        def definition = buildBeanDefinition('anntest.Test', source('''
@Prototype
class Test extends Parent

@MyQ
@MyS
trait Parent
''' + MARKERS))

        then: 'the bean exists on its own annotation'
        definition.hasDeclaredAnnotation(Prototype)

        and: 'and the trait\u0027s annotations crossed, as inherited rather than declared'
        definition.hasAnnotation('anntest.MyQ')
        definition.hasAnnotation('anntest.MyS')
        !definition.hasDeclaredAnnotation('anntest.MyQ')
        !definition.hasDeclaredAnnotation('anntest.MyS')

        and: 'the qualifier resolves, while the scope declared here still wins'
        definition.declaredQualifier.toString() == '@MyQ'
        definition.scopeName.get() == Prototype.name
    }

    void "does not inherit an annotation through a trait when it is not declared inherited"() {
        when: 'the trait carries a scope and qualifier that are not @Inherited'
        def definition = buildBeanDefinition('anntest.Test', source('''
@Prototype
class Test extends Parent

@Singleton
@Named("test")
trait Parent
'''))

        then: 'walking the interface does not make them inherited -- @Inherited still decides'
        definition.hasDeclaredAnnotation(Prototype)
        !definition.hasAnnotation(AnnotationUtil.NAMED)
        !definition.hasAnnotation(AnnotationUtil.QUALIFIER)
        definition.declaredQualifier == null
        definition.scopeName.get() == Prototype.name
        !definition.isSingleton()
    }
}
