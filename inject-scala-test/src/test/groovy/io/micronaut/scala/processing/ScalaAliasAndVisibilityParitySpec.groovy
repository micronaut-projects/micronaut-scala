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
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P0 parity, ported from {@code inject-java}'s {@code AliasForQualifierSpec},
 * {@code VisibilityIssuesSpec} and {@code AnnotatedFieldWithSetterSpec}.
 *
 * <p>{@code @AliasFor} makes one annotation member stand for another's, so a custom qualifier can
 * be written without repeating {@code @Named}. The alias is resolved when metadata is built, and
 * this branch has already had to correct that path twice -- once for the order aliases were
 * applied in, once for the container Core reads them through.</p>
 *
 * <p>Visibility is the other half. Scala compiles {@code protected} and {@code protected[pkg]} to
 * public members, so a member Java would consider inaccessible is accessible here, and the model
 * has to report what the bytecode contains rather than what the source says.</p>
 */
class ScalaAliasAndVisibilityParitySpec extends AbstractScalaTypeElementSpec {

    void "resolves a custom qualifier written through AliasFor"() {
        when: 'the qualifier declares its own member and aliases it onto @Named'
        def context = buildContext('''
package aliasvis

import io.micronaut.context.annotation.AliasFor
import jakarta.inject.Named
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class Flavour(@AliasFor(annotation = classOf[Named], member = "value") val value: String)
  extends StaticAnnotation

trait Drink:
  def name(): String

@Singleton
@Flavour("cola")
class Cola extends Drink:
  override def name(): String = "cola"

@Singleton
@Flavour("lemonade")
class Lemonade extends Drink:
  override def name(): String = "lemonade"
''', [:], true)
        def drinkType = context.classLoader.loadClass('aliasvis.Drink')

        then: 'the alias makes the member act as the name, so byName resolves each'
        context.getBean(drinkType, Qualifiers.byName('cola')).name() == 'cola'
        context.getBean(drinkType, Qualifiers.byName('lemonade')).name() == 'lemonade'
    }

    void "writes the aliased member onto the annotation it aliases"() {
        when:
        def definition = buildBeanDefinition('aliasvis.Cola', '''
package aliasvis

import io.micronaut.context.annotation.AliasFor
import jakarta.inject.Named
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class Flavour(@AliasFor(annotation = classOf[Named], member = "value") val value: String)
  extends StaticAnnotation

@Singleton
@Flavour("cola")
class Cola
''')

        then: 'the definition carries @Named with the aliased value, not only the custom one'
        definition.stringValue(AnnotationUtil.NAMED).get() == 'cola'
        definition.stringValue('aliasvis.Flavour').get() == 'cola'
    }

    void "reports a protected member as public, because that is what it compiles to"() {
        when:
        def element = buildClassElement('aliasvis.Holder', '''
package aliasvis

class Holder:
  protected def visibleAnyway(): String = "x"
  private def genuinelyPrivate(): String = "y"
''')
        def names = element.methods*.name

        then: '''Scala emits a protected member as public, so the model reports it public. The
                 source says otherwise, and the bytecode is what a caller and a proxy see'''
        names.contains('visibleAnyway')
        element.methods.find { it.name == 'visibleAnyway' }.isPublic()

        and: 'while a private member stays private'
        !element.methods.find { it.name == 'genuinelyPrivate' }?.isPublic()
    }
}
