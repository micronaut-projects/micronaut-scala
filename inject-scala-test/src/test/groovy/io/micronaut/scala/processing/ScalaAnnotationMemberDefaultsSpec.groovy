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

import io.micronaut.core.annotation.AnnotationClassValue
import io.micronaut.core.annotation.AnnotationValue
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaAnnotationDefaultsRecordingVisitor

/**
 * The Scala counterpart of Core's {@code AnnotationMemberDefaultsSpec}, which the Java, Kotlin and
 * Groovy modules each carry: every language has to report the same defaults for the equivalent
 * annotation, rendered to the same literal.
 */
class ScalaAnnotationMemberDefaultsSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package defaults

import io.micronaut.context.annotation.Bean
import scala.annotation.StaticAnnotation

enum Colour:
  case RED, GREEN

class Nested(val name: String = "nested") extends StaticAnnotation

class Defaults(
    val stringValue: String = "foo",
    val emptyStringValue: String = "",
    val intValue: Int = 42,
    val enumValue: Colour = Colour.GREEN,
    val classValue: Class[?] = classOf[String],
    val stringArray: Array[String] = Array("a", "b"),
    val emptyArray: Array[String] = Array(),
    val nested: Nested = new Nested(name = "n")
) extends StaticAnnotation

@Defaults
@Bean
class Test
'''

    /**
     * The defaults reported by {@code VisitorContext.getAnnotationDefaultValues}, which asks for every
     * declared default including empty ones. Verbatim the literal the three Core suites share.
     */
    static final String EXPECTED_FROM_CONTEXT =
            "[classValue:AnnotationClassValue(java.lang.String), " +
            "emptyArray:String[], " +
            "emptyStringValue:String(), " +
            "enumValue:String(GREEN), " +
            "intValue:Integer(42), " +
            "nested:AnnotationValue(defaults.Nested, [name:n]), " +
            "stringArray:String[String(a), String(b)], " +
            "stringValue:String(foo)]"

    /**
     * The defaults baked into the written annotation metadata and seen through
     * {@code AnnotationValue.getDefaultValues()}: the same, less the empty string default, which
     * Core omits to keep the generated metadata small.
     */
    static final String EXPECTED_FROM_ANNOTATION =
            "[classValue:AnnotationClassValue(java.lang.String), " +
            "emptyArray:String[], " +
            "enumValue:String(GREEN), " +
            "intValue:Integer(42), " +
            "nested:AnnotationValue(defaults.Nested, [name:n]), " +
            "stringArray:String[String(a), String(b)], " +
            "stringValue:String(foo)]"

    private ScalaAnnotationDefaultsRecordingVisitor.Recording compileTestSource() {
        ScalaAnnotationDefaultsRecordingVisitor.record('defaults.Test', 'defaults.Defaults') {
            buildBeanDefinition('defaults.Test', SOURCE)
        }
    }

    void 'every annotation member default is reported through the visitor context'() {
        when:
        def defaults = compileTestSource().fromContext

        then: 'every declared default is reported, including the empty string and the empty array'
        defaults != null
        defaults.keySet()*.toString() as Set == [
                'stringValue', 'emptyStringValue', 'intValue', 'enumValue',
                'classValue', 'stringArray', 'emptyArray', 'nested'
        ] as Set

        and: 'constants, enum constants, class literals, arrays and nested annotations all resolve'
        defaults['stringValue'] == 'foo'
        defaults['emptyStringValue'] == ''
        defaults['intValue'] == 42
        defaults['enumValue'] == 'GREEN'
        defaults['classValue'] instanceof AnnotationClassValue
        defaults['classValue'].name == 'java.lang.String'
        defaults['stringArray'] as List == ['a', 'b']
        defaults['emptyArray'].length == 0
        defaults['nested'] instanceof AnnotationValue
        defaults['nested'].annotationName == 'defaults.Nested'
        defaults['nested'].stringValue('name').get() == 'n'

        and: 'the result is identical to the other three languages'
        ScalaAnnotationDefaultsRecordingVisitor.describe(defaults) == EXPECTED_FROM_CONTEXT
    }

    void 'annotation member defaults are visible on the AnnotationValue read from the element API'() {
        when:
        def defaults = compileTestSource().fromAnnotation

        then: 'the written metadata carries every default except the empty string'
        defaults != null
        defaults.keySet()*.toString() as Set == [
                'stringValue', 'intValue', 'enumValue',
                'classValue', 'stringArray', 'emptyArray', 'nested'
        ] as Set

        and:
        defaults['enumValue'] == 'GREEN'
        defaults['classValue'].name == 'java.lang.String'
        defaults['stringArray'] as List == ['a', 'b']
        defaults['emptyArray'].length == 0
        defaults['nested'].stringValue('name').get() == 'n'

        and: 'the result is identical to the other three languages'
        ScalaAnnotationDefaultsRecordingVisitor.describe(defaults) == EXPECTED_FROM_ANNOTATION
    }

    void 'the defaults of a Java annotation on the classpath are reported the same way'() {
        when: 'ExternalDefaulted declares value = "fallback", enabled = true and qualifier = ""'
        def recording = ScalaAnnotationDefaultsRecordingVisitor.record('defaults.Target', 'io.micronaut.scala.processing.fixtures.ExternalDefaulted') {
            buildClassElement('defaults.Target', '''
package defaults

import io.micronaut.scala.processing.fixtures.ExternalDefaulted

@ExternalDefaulted
class Target
''')
        }

        then: 'the context reports the empty string default, and the written metadata omits it'
        recording.fromContext.keySet()*.toString() as Set == ['value', 'enabled', 'qualifier'] as Set
        recording.fromContext['qualifier'] == ''
        recording.fromAnnotation.keySet()*.toString() as Set == ['value', 'enabled'] as Set
        recording.fromAnnotation['value'] == 'fallback'
        recording.fromAnnotation['enabled'] == true
    }
}
