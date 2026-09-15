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

import io.micronaut.core.type.Argument
import io.micronaut.core.type.GenericPlaceholder
import io.micronaut.inject.ast.ClassElement
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * The Scala counterpart of Core's {@code IntrospectionTypeArgumentsSpec} (Java, Kotlin and
 * Groovy) and of the Kotlin {@code CollectionIntrospectionSpec}: the type arguments an
 * introspection and a bean definition report for the types above the introspected one, bound
 * through every level of the hierarchy and expressed in the variables of the introspected type.
 */
class ScalaIntrospectionTypeArgumentsParitySpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton
import java.util.Iterator

trait Validator[A, T]:
  def isValid(value: T): Boolean

trait StringIterable extends java.lang.Iterable[String]

@Introspected
@Singleton
class Bound extends Validator[CharSequence, Number]:
  def isValid(value: Number): Boolean = true

@Introspected
@Singleton
class Indirect extends StringIterable:
  def iterator(): Iterator[String] = null

@Introspected
@Singleton
class Open[T] extends Validator[CharSequence, T]:
  def isValid(value: T): Boolean = true

@Introspected
@Singleton
class Plain
'''

    private static final String HIERARCHY = '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton
import java.util.ArrayList
import java.util.HashMap
import java.util.List

trait Container[E, F]

trait Flipped[F, E] extends Container[E, F]

@Introspected
@Singleton
class Reversed[A, B] extends HashMap[B, A]

@Introspected
@Singleton
class Swapped[K, V] extends HashMap[V, K]

@Introspected
@Singleton
class Holder[X, Y] extends Flipped[X, Y]

@Introspected
@Singleton
class Strings extends ArrayList[String]

@Introspected
@Singleton
class Nested[X] extends ArrayList[List[X]]
'''

    private static final String ANNOTATED = '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton
import scala.annotation.StaticAnnotation

class Marker extends StaticAnnotation

@Introspected
@Singleton
class AnnotatedLeaf[X] extends AnnotatedMiddle[X]

trait Container2[E, F]

trait AnnotatedMiddle[T] extends Container2[T @Marker, T]

@Introspected
@Singleton
class AnnotatedStrings extends AnnotatedMiddle[String]
'''

    void 'an introspected type reports the arguments it binds in a trait'() {
        given:
        def introspection = buildBeanIntrospection('test.Bound', SOURCE)

        expect:
        introspection.getTypeArguments('test.Validator')*.type == [CharSequence, Number]
        introspection.getTypeArguments('test.Validator')*.name == ['A', 'T']
    }

    void 'an introspection reports the same arguments as the bean definition for the same class'() {
        given:
        def introspection = buildBeanIntrospection('test.Bound', SOURCE)
        def definition = buildBeanDefinition('test.Bound', SOURCE)

        expect:
        introspection.getTypeArguments('test.Validator') == definition.getTypeArguments('test.Validator')
        introspection.getTypeArguments() == definition.getTypeArguments()
    }

    void 'arguments bound through an intermediate trait are reported'() {
        given:
        def introspection = buildBeanIntrospection('test.Indirect', SOURCE)

        expect:
        introspection.getTypeArguments(Iterable)*.type == [String]
        introspection.getTypeArguments(Iterable) == buildBeanDefinition('test.Indirect', SOURCE).getTypeArguments(Iterable)
    }

    void 'an argument left open is reported as the type variable'() {
        given:
        def introspection = buildBeanIntrospection('test.Open', SOURCE)
        def definition = buildBeanDefinition('test.Open', SOURCE)
        def arguments = introspection.getTypeArguments('test.Validator')

        expect:
        arguments*.type == [CharSequence, Object]
        arguments[1].isTypeVariable()
        arguments == definition.getTypeArguments('test.Validator')

        and: 'the no-argument overload reports what the type itself declares'
        introspection.getTypeArguments()*.name == ['T']
        introspection.getTypeArguments() == definition.getTypeArguments()
    }

    void 'an introspected type that binds nothing reports an empty list'() {
        given:
        def introspection = buildBeanIntrospection('test.Plain', SOURCE)

        expect:
        introspection.getTypeArguments() == []
        introspection.getTypeArguments('test.Validator') == []
        introspection.getTypeArguments(Iterable) == []
        introspection.getTypeArguments((Class) null) == []
        introspection.getTypeArguments((String) null) == []
    }

    void 'a type two levels above is reported in the variables of the introspected type'() {
        given:
        def introspection = buildBeanIntrospection('test.Reversed', HIERARCHY)
        def definition = buildBeanDefinition('test.Reversed', HIERARCHY)

        expect:
        variableNames(introspection.getTypeArguments(HashMap)) == ['B', 'A']
        introspection.getTypeArguments(Map)*.name == ['K', 'V']
        variableNames(introspection.getTypeArguments(Map)) == ['B', 'A']
        variableNames(definition.getTypeArguments(Map)) == ['B', 'A']
        introspection.getTypeArguments(Map) == definition.getTypeArguments(Map)
    }

    void 'a variable of the introspected type named like one of the super type is still bound by position'() {
        given:
        def introspection = buildBeanIntrospection('test.Swapped', HIERARCHY)
        def definition = buildBeanDefinition('test.Swapped', HIERARCHY)

        expect:
        variableNames(introspection.getTypeArguments(HashMap)) == ['V', 'K']
        variableNames(introspection.getTypeArguments(Map)) == ['V', 'K']
        variableNames(definition.getTypeArguments(Map)) == ['V', 'K']
    }

    void 'a trait reached through an intermediate trait is reported in the variables of the introspected type'() {
        given:
        def introspection = buildBeanIntrospection('test.Holder', HIERARCHY)
        def definition = buildBeanDefinition('test.Holder', HIERARCHY)

        expect:
        variableNames(introspection.getTypeArguments('test.Flipped')) == ['X', 'Y']
        introspection.getTypeArguments('test.Container')*.name == ['E', 'F']
        variableNames(introspection.getTypeArguments('test.Container')) == ['Y', 'X']
        variableNames(definition.getTypeArguments('test.Container')) == ['Y', 'X']
    }

    void 'a concrete type bound one level up is reported for every type above'() {
        given:
        def introspection = buildBeanIntrospection('test.Strings', HIERARCHY)
        def definition = buildBeanDefinition('test.Strings', HIERARCHY)

        expect:
        introspection.getTypeArguments(Iterable)*.type == [String]
        !introspection.getTypeArguments(Iterable)[0].isTypeVariable()
        introspection.getTypeArguments(Collection)*.type == [String]
        definition.getTypeArguments(Iterable)*.type == [String]
    }

    void 'a variable nested in a type bound one level up is reported in the variables of the introspected type'() {
        given:
        def introspection = buildBeanIntrospection('test.Nested', HIERARCHY)
        def definition = buildBeanDefinition('test.Nested', HIERARCHY)
        def element = introspection.getTypeArguments(Iterable)[0]

        expect:
        element.type == List
        variableNames(element.typeParameters as List) == ['X']
        variableNames(definition.getTypeArguments(Iterable)[0].typeParameters as List) == ['X']
    }

    void 'a type that binds nothing anywhere still answers for a type it does not implement'() {
        given:
        def introspection = buildBeanIntrospection('test.Bound', SOURCE)

        expect:
        introspection.getTypeArguments(Comparable) == []
        introspection.getTypeArguments('does.not.Exist') == []
    }

    void 'type annotations written where a variable is used survive the binding'() {
        given:
        def introspection = buildBeanIntrospection('test.AnnotatedLeaf', ANNOTATED)
        def definition = buildBeanDefinition('test.AnnotatedLeaf', ANNOTATED)
        def arguments = introspection.getTypeArguments('test.Container2')

        expect: 'the annotation written where the intermediate trait uses the variable is kept'
        arguments[0].annotationMetadata.hasAnnotation('test.Marker')
        !arguments[1].annotationMetadata.hasAnnotation('test.Marker')
        definition.getTypeArguments('test.Container2')[0].annotationMetadata.hasAnnotation('test.Marker')

        and: 'the argument the use does not annotate is the variable of the introspected type'
        variableNames(arguments)[1] == 'X'
    }

    void 'a use that annotates the variable keeps its annotation through the binding'() {
        expect:
        buildClassElement('test.Test', '''
package test

import scala.annotation.StaticAnnotation

class Test[X] extends AnnotatedMiddle[X]

trait Container2[E, F]

trait AnnotatedMiddle[T] extends Container2[T @Marker, T]

class Marker extends StaticAnnotation
''') { ClassElement leaf ->
            def arguments = leaf.getAllTypeArguments().get('test.Container2')

            assert arguments.E.typeAnnotationMetadata.annotationMetadata.hasAnnotation('test.Marker')
            assert !arguments.F.typeAnnotationMetadata.annotationMetadata.hasAnnotation('test.Marker')

            // Java answers the annotated use as the intermediate type writes it, `T`, because in
            // its model the annotation belongs to that use alone and binding it would lose the
            // annotation. The Scala model substitutes the supertype's arguments as it reads them,
            // so the bound variable is `X` in both positions and the annotation survives on it:
            // nothing is lost, and the name is the one the leaf declares.
            assert arguments.values()*.variableName == ['X', 'X']
        }
    }

    void 'a variable bound to a concrete type is reported for every type above'() {
        given:
        def introspection = buildBeanIntrospection('test.AnnotatedStrings', ANNOTATED)
        def definition = buildBeanDefinition('test.AnnotatedStrings', ANNOTATED)

        expect:
        introspection.getTypeArguments('test.Container2')*.type == [String, String]
        definition.getTypeArguments('test.Container2')*.type == [String, String]
    }

    void 'an introspection of a HashMap subclass has unique properties'() {
        when:
        def introspection = buildBeanIntrospection('test.Reversed', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton

@Introspected
@Singleton
class Reversed[A, B] extends java.util.HashMap[B, A]
''')

        then: 'each inherited collection property is generated once'
        introspection.beanProperties*.name == introspection.beanProperties*.name.unique()
        introspection.beanProperties*.name.containsAll(['empty'])

        and: 'the properties can be read'
        def instance = introspection.instantiate()
        introspection.beanProperties.every { it.get(instance) != null }
    }

    void 'an introspection of an ArrayList subclass has unique properties'() {
        when:
        def introspection = buildBeanIntrospection('test.Strings', '''
package test

import io.micronaut.core.annotation.Introspected
import jakarta.inject.Singleton

@Introspected
@Singleton
class Strings extends java.util.ArrayList[String]
''')

        then: 'each inherited collection property is generated once'
        introspection.beanProperties*.name == introspection.beanProperties*.name.unique()
        introspection.beanProperties*.name.containsAll(['empty'])

        and: 'a Java field without accessors is not a property'
        !introspection.getProperty('modCount').isPresent()

        and: 'the properties can be read'
        introspection.getRequiredProperty('empty', boolean).get(introspection.instantiate()) == true
    }

    private static List<String> variableNames(List<Argument<?>> arguments) {
        arguments.collect { it instanceof GenericPlaceholder ? it.variableName : null }
    }
}
