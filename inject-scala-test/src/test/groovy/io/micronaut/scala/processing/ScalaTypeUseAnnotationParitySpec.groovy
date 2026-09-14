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

import jakarta.inject.Singleton
import jakarta.validation.Valid
import jakarta.validation.constraints.Min

/**
 * P1 parity, ported from {@code inject-java}'s {@code AnnotationsOnGenericTypesSpec}.
 *
 * <p>An annotation written at a type argument has to reach the written definition attached to
 * that argument, and it has to arrive alone: the annotations on the argument's <em>class</em>
 * are a different thing, and merging them in makes a validated bean out of anything whose
 * element type happens to be a {@code @Singleton}.</p>
 *
 * <p>Type-use annotations have already needed two corrections on this branch -- the order they
 * are peeled in, and where an annotation member is read from. Both were found at the element
 * model. This asserts the other end, on the arguments a running definition hands back, where the
 * separation between the use and the declaration is what validation depends on.</p>
 */
class ScalaTypeUseAnnotationParitySpec extends AbstractScalaTypeElementSpec {

    void "carries a type-use annotation through to an executable method's type argument"() {
        when: '''the annotation is written at the argument, which Scala spells after the type.
                 `value` has to be named: Scala matches a positional argument to a Java
                 annotation member by declaration order, so bare `@Min(10)` binds to `message`'''
        def definition = buildBeanDefinition('typeuse.Test', '''
package typeuse

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.constraints.Min

@Singleton
class Test:
  @Executable
  def test(values: java.util.List[java.lang.Long @Min(value = 10)]): Unit = ()
''')
        def method = definition.getRequiredMethod('test', List)

        then:
        method.arguments[0].typeParameters[0].annotationMetadata.hasAnnotation(Min)
        method.arguments[0].typeParameters[0].annotationMetadata.intValue(Min).asInt == 10
    }

    void "does not merge the argument class's own annotations into the type argument"() {
        when: 'Foo is a @Singleton, and @Valid is written at the use'
        def definition = buildBeanDefinition('typeuse.Test', '''
package typeuse

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.Valid

@Singleton
class Foo

@Singleton
class Test:
  @Executable
  def test(values: java.util.List[Foo @Valid]): Unit = ()
''')
        def metadata = definition.getRequiredMethod('test', List).arguments[0].typeParameters[0].annotationMetadata

        then: 'the annotation written at the use is there'
        metadata.hasAnnotation(Valid)

        and: '''and the one declared on the class is not. `List[Foo]` says nothing about Foo
                beyond naming it, so an annotation Foo happens to carry must not read as though
                it had been written at the use -- @Introspected, @Singleton, or a validation
                annotation that acts by its presence alone'''
        !metadata.hasAnnotation(Singleton)
    }

    void "does not merge the parameter class's own annotations into the parameter"() {
        when:
        def definition = buildBeanDefinition('typeuse.Test', '''
package typeuse

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.Valid

@Singleton
class Foo

@Singleton
class Test:
  @Executable
  def test(@Valid value: Foo): Unit = ()
''')
        def fooType = definition.beanType.classLoader.loadClass('typeuse.Foo')
        def metadata = definition.getRequiredMethod('test', fooType).arguments[0].annotationMetadata

        then:
        metadata.hasAnnotation(Valid)
        !metadata.hasAnnotation(Singleton)
    }

    void "carries a type-use annotation on a return type's argument"() {
        when:
        def definition = buildBeanDefinition('typeuse.Test', '''
package typeuse

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import jakarta.validation.constraints.Min

@Singleton
class Test:
  @Executable
  def test(values: java.util.List[java.lang.Long @Min(value = 10)]): java.util.List[java.lang.Long @Min(value = 10)] =
    values
''')
        def method = definition.getRequiredMethod('test', List)

        then: 'the return type is annotated independently of the parameter'
        method.returnType.asArgument().typeParameters[0].annotationMetadata.hasAnnotation(Min)
    }

    void "carries a type-use annotation on an injected field's type argument"() {
        when:
        def definition = buildBeanDefinition('typeuse.Test', '''
package typeuse

import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.validation.constraints.Min

@Singleton
class Test:
  @Inject
  var values: java.util.List[java.lang.Long @Min(value = 10)] = null
''')

        then: 'an injection point records it the same way an executable argument does'
        definition.injectedMethods.size() + definition.injectedFields.size() > 0
        (definition.injectedFields*.asArgument() + definition.injectedMethods*.arguments.flatten())
            .any { it.typeParameters.length > 0 && it.typeParameters[0].annotationMetadata.hasAnnotation(Min) }
    }
}
