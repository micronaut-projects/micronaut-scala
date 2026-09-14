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

import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code AnnotationQualifierSpec},
 * {@code NamedQualifierSpec}, {@code ReplacesSpec} and {@code MultipleQualifiersSpec}.
 *
 * <p>A qualifier decides which of several candidates satisfies a point, and every way of writing
 * one has to reach the definition: a custom {@code @Qualifier}-stereotyped annotation, two of
 * them at once, a name derived from the class rather than written, and {@code @Replaces}, which
 * is a qualifier decision made about a bean that already exists.</p>
 *
 * <p>Each of these fails by choosing wrongly rather than by failing, which is the case an
 * assertion is for. Qualifier coverage here is by explicit {@code @Named}; these are the forms
 * that depend on the annotation model rather than on a string.</p>
 */
class ScalaQualifierParitySpec extends AbstractScalaTypeElementSpec {

    private static final String QUALIFIERS = '''
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class Fast extends StaticAnnotation

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class Reliable extends StaticAnnotation
'''

    private static String source(String body) {
        '''
package qualifierparity

import jakarta.inject.Inject
import jakarta.inject.Named
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

trait Engine:
  def name(): String
''' + body + QUALIFIERS
    }

    void "selects a candidate by a custom qualifier annotation"() {
        when:
        def context = buildContext(source('''
@Singleton
@Fast
class FastEngine extends Engine:
  override def name(): String = "fast"

@Singleton
@Reliable
class ReliableEngine extends Engine:
  override def name(): String = "reliable"

@Singleton
class Vehicle(@Fast val engine: Engine)
'''), [:], true)

        then: 'the annotation, not a name, picks one of two candidates'
        getBean(context, 'qualifierparity.Vehicle').engine().name() == 'fast'

        cleanup:
        context?.close()
    }

    void "requires every qualifier a point declares"() {
        when: 'two qualifiers at once, satisfied by only one candidate'
        def context = buildContext(source('''
@Singleton
@Fast
class FastEngine extends Engine:
  override def name(): String = "fast"

@Singleton
@Fast
@Reliable
class FastReliableEngine extends Engine:
  override def name(): String = "both"

@Singleton
class Vehicle(@Fast @Reliable val engine: Engine)
'''), [:], true)

        then: 'the candidate carrying only one of them does not satisfy the point'
        getBean(context, 'qualifierparity.Vehicle').engine().name() == 'both'

        cleanup:
        context?.close()
    }

    void "names a bean after its class when no name is written"() {
        when:
        def context = buildContext(source('''
@Singleton
class FastEngine extends Engine:
  override def name(): String = "fast"

@Singleton
class ReliableEngine extends Engine:
  override def name(): String = "reliable"
'''), [:], true)
        def engineType = context.classLoader.loadClass('qualifierparity.Engine')

        then: 'the implicit name is the simple class name, decapitalised'
        context.getBean(engineType, Qualifiers.byName('fastEngine')).name() == 'fast'
        context.getBean(engineType, Qualifiers.byName('reliableEngine')).name() == 'reliable'

        cleanup:
        context?.close()
    }

    void "replaces a bean with another of the same type"() {
        when:
        def context = buildContext(source('''
import io.micronaut.context.annotation.Replaces

@Singleton
class DefaultEngine extends Engine:
  override def name(): String = "default"

@Singleton
@Replaces(classOf[DefaultEngine])
class CustomEngine extends Engine:
  override def name(): String = "custom"
'''), [:], true)
        def engineType = context.classLoader.loadClass('qualifierparity.Engine')

        then: 'only the replacement is a candidate, so the lookup is not ambiguous'
        context.getBeansOfType(engineType).size() == 1
        context.getBean(engineType).name() == 'custom'

        cleanup:
        context?.close()
    }
}
