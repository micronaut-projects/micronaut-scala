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

import io.micronaut.context.annotation.AliasFor
import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Requirements
import io.micronaut.core.annotation.TypeHint
import io.micronaut.inject.processing.ProcessingException
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotatingVisitor
import jakarta.inject.Named
import jakarta.inject.Qualifier
import jakarta.inject.Singleton

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.util.function.Supplier

class ScalaAnnotationMetadataParitySpec extends AbstractScalaTypeElementSpec {

    void "reads Scala annotation defaults nested annotations class literals enums and arrays"() {
        when:
        def element = buildClassElement('annmetadata.Engine', '''
package annmetadata

import io.micronaut.context.annotation.Requires
import io.micronaut.context.annotation.Requirements
import io.micronaut.core.annotation.TypeHint
import scala.annotation.StaticAnnotation

class Marker(val name: String = "default", val enabled: Boolean = true) extends StaticAnnotation

@Marker
@Requirements(Array(
  new Requires(env = Array("test", "dev")),
  new Requires(property = "engine.enabled", value = "true")
))
@TypeHint(value = Array(classOf[String]), accessType = Array(TypeHint.AccessType.ALL_PUBLIC))
class Engine
''')
        def marker = element.getAnnotation('annmetadata.Marker')
        def requirements = element.getAnnotationValuesByType(Requires)
        def typeHint = element.getAnnotation(TypeHint)

        then:
        marker.stringValue('name').get() == 'default'
        marker.booleanValue('enabled').get()
        element.hasAnnotation(Requirements)
        requirements.size() == 2
        requirements[0].stringValues('env').toList() == ['test', 'dev']
        requirements[1].stringValue('property').get() == 'engine.enabled'
        requirements[1].stringValue('value').get() == 'true'
        typeHint.annotationClassValues('value').collect { it.name } == [String.name]
        typeHint.enumValues('accessType', TypeHint.AccessType).toList() == [TypeHint.AccessType.ALL_PUBLIC]
    }

    void "reads Scala source-defined retention targets stereotypes and aliases"() {
        when:
        def element = buildClassElement('annmetadata.Engine', '''
package annmetadata

import io.micronaut.context.annotation.AliasFor
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Singleton
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
class EngineStereotype(
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val value: String = "primary"
) extends StaticAnnotation

@EngineStereotype
class Engine
''')
        def marker = buildClassElement('annmetadata.EngineStereotype', '''
package annmetadata

import io.micronaut.context.annotation.AliasFor
import jakarta.inject.Named
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
import scala.annotation.meta.getter

@Singleton
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
class EngineStereotype(
  @(AliasFor @getter)(annotation = classOf[Named], member = "value")
  val value: String = "primary"
) extends StaticAnnotation

@EngineStereotype
class Engine
''')

        then:
        element.hasAnnotation('annmetadata.EngineStereotype')
        element.hasStereotype(Singleton)
        element.hasStereotype(Qualifier)
        element.getAnnotationValuesByStereotype(Named.name).find {
            it.annotationName == 'annmetadata.EngineStereotype'
        }.stringValue().get() == 'primary'
        marker.getAnnotation(Retention).enumValue('value', RetentionPolicy).get() == RetentionPolicy.RUNTIME
        marker.getAnnotation(Target).enumValues('value', ElementType).toList() == [ElementType.TYPE, ElementType.METHOD]
    }

    void "reports ProcessingException messages with originating Scala elements"() {
        when:
        ScalaAnnotatingVisitor.withClassFailure(new ProcessingException(null, null), {
            buildClassElement('annmetadata.Engine', '''
package annmetadata

class Engine
''')
        } as Supplier)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('Error processing Scala element [annmetadata.Engine]')
        e.message.contains('io.micronaut.inject.processing.ProcessingException')
    }
}
