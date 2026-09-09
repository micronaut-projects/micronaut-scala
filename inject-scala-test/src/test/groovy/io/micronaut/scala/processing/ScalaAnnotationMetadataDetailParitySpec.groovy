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

import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.AnnotationUtil
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import spock.lang.PendingFeature

/**
 * P0 parity, ported from {@code inject-java}'s {@code AnnotationMetadataWriterSpec},
 * {@code AnnotationMetadataHierarchySpec}, {@code AnnotationDefaultValuesSpec},
 * {@code ArgumentAnnotationMetadataSpec}, {@code BeanDefinitionAnnotationMetadataSpec} and
 * {@code InheritedNullableAnnotationSpec}.
 *
 * <p>Annotation coverage here is on the element model. These assert the other side of the write:
 * what a definition loaded from its class file reports. A default that is resolved at build time
 * but not written, a parameter annotation that reaches the element but not the argument, or a
 * class-level annotation that does not merge into a method's metadata are all invisible until
 * something reads the definition back.</p>
 */
class ScalaAnnotationMetadataDetailParitySpec extends AbstractScalaTypeElementSpec {

    void "writes an annotation member's default when the author omits it"() {
        when:
        def definition = buildBeanDefinition('metadetail.Test', '''
package metadetail

import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
class Marker(val name: String = "fallback", val count: Int = 7) extends StaticAnnotation

@Singleton
@Marker(name = "given")
class Test
''')

        then: 'the member written explicitly is what the author wrote'
        definition.stringValue('metadetail.Marker', 'name').get() == 'given'

        and: 'and the one omitted resolves to its declared default rather than to nothing'
        definition.intValue('metadetail.Marker', 'count').asInt == 7
    }

    void "writes the annotations of an executable method's arguments"() {
        when:
        def definition = buildBeanDefinition('metadetail.Test', '''
package metadetail

import io.micronaut.context.annotation.Executable
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
class Marker(val value: String) extends StaticAnnotation

@Singleton
class Test:
  @Executable
  def run(@Marker("first") one: String, @Named("second") two: String): String = one + two
''')
        def method = definition.getRequiredMethod('run', String, String)

        then: 'each argument keeps its own annotations, in position'
        method.arguments[0].annotationMetadata.stringValue('metadetail.Marker').get() == 'first'
        method.arguments[1].annotationMetadata.stringValue(AnnotationUtil.NAMED).get() == 'second'

        and: 'and does not pick up the other argument\'s'
        !method.arguments[0].annotationMetadata.hasAnnotation(AnnotationUtil.NAMED)
    }

    void "merges a class-level annotation into a method's metadata without declaring it there"() {
        when:
        def definition = buildBeanDefinition('metadetail.Test', '''
package metadetail

import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
@Requires(property = "from.class")
class Test:
  @Executable
  @Requires(property = "from.method")
  def run(): String = "ok"
''')
        def method = definition.getRequiredMethod('run')

        then: 'the method sees both'
        method.getAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null).toSet() ==
            ['from.class', 'from.method'] as Set

        and: 'but declares only its own, which is the difference a hierarchy exists to keep'
        method.getDeclaredAnnotationValuesByType(Requires)*.stringValue('property')*.orElse(null) ==
            ['from.method']
    }

    @PendingFeature(reason = 'Nullability is a type-use annotation and reaches the method '
        + 'through the type path rather than the annotation hierarchy, so it is not carried '
        + 'from an abstract trait member the way @Executable now is')
    void "writes a nullability annotation a method inherits"() {
        when: 'the trait declares the nullability, the class only implements'
        def definition = buildBeanDefinition('metadetail.Test', '''
package metadetail

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton
import org.jspecify.annotations.Nullable

trait Source:
  @Executable
  @Nullable
  def find(): String

@Singleton
class Test extends Source:
  override def find(): String = null
''')

        then:
        definition.getRequiredMethod('find').annotationMetadata.hasStereotype(AnnotationUtil.NULLABLE)
    }

    void "keeps a source-retention annotation out of the written definition"() {
        when:
        def definition = buildBeanDefinition('metadetail.Test', '''
package metadetail

import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.SOURCE)
class BuildOnly extends StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
class KeptAtRuntime extends StaticAnnotation

@Singleton
@BuildOnly
@KeptAtRuntime
class Test
''')

        then: 'retention decides what survives into the class file'
        definition.hasAnnotation('metadetail.KeptAtRuntime')
        !definition.hasAnnotation('metadetail.BuildOnly')
    }
}
