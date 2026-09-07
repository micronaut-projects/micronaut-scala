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
import io.micronaut.scala.processing.test.ScalaCompiler
import io.micronaut.scala.processing.test.ScalaDiagnosticVisitor

/**
 * A visitor diagnostic must be attributed to the element the visitor blamed. The whole
 * reporter channel used to be {@code Consumer<String>}, so the originating element was
 * discarded before it reached {@code report.error} and every Micronaut diagnostic was
 * emitted against {@code NoSourcePosition} -- no file, no line, no caret.
 */
class ScalaDiagnosticPositionSpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package test

import jakarta.inject.Singleton

@Singleton
class Target {

  var counter: Int = 0

  def compute(value: Int): Int = value + counter
}
'''

    private ScalaCompiler.Diagnostics diagnostics(
            ScalaDiagnosticVisitor.Severity severity,
            ScalaDiagnosticVisitor.Target target,
            String message) {
        ScalaCompiler.Diagnostics result = null
        ScalaDiagnosticVisitor.reporting(severity, target, message, {
            result = ScalaCompiler.buildAndGetDiagnostics('test.Target', SOURCE)
        })
        result
    }

    void 'reports a visitor error at the position of the class it blamed'() {
        when:
        def result = diagnostics(
                ScalaDiagnosticVisitor.Severity.FAIL,
                ScalaDiagnosticVisitor.Target.CLASS,
                'class is not acceptable')

        then:
        def error = result.errors().find { it.contains('class is not acceptable') }
        error != null
        // `class Target` is on line 7 of the source above.
        error.startsWith('Target.scala:7:')
    }

    void 'reports a visitor error at the position of the method it blamed'() {
        when:
        def result = diagnostics(
                ScalaDiagnosticVisitor.Severity.FAIL,
                ScalaDiagnosticVisitor.Target.METHOD,
                'method is not acceptable')

        then:
        def error = result.errors().find { it.contains('method is not acceptable') }
        error != null
        // `def compute` is on line 11, which is what distinguishes this from the class
        // position: without the element the diagnostic would carry no position at all.
        error.startsWith('Target.scala:11:')
    }

    void 'reports a visitor warning at the position of the field it blamed'() {
        when:
        def result = diagnostics(
                ScalaDiagnosticVisitor.Severity.WARN,
                ScalaDiagnosticVisitor.Target.FIELD,
                'field is questionable')

        then:
        def warning = result.warnings().find { it.contains('field is questionable') }
        warning != null
        // `var counter` is on line 9.
        warning.startsWith('Target.scala:9:')
        and: 'a warning does not fail the compilation'
        result.errors().every { !it.contains('field is questionable') }
    }

    void 'reports without a position when the visitor named no element'() {
        when:
        def result = diagnostics(
                ScalaDiagnosticVisitor.Severity.FAIL,
                ScalaDiagnosticVisitor.Target.NONE,
                'nothing in particular is wrong')

        then: 'the message still arrives, simply with nothing to point at'
        def error = result.errors().find { it.contains('nothing in particular is wrong') }
        error != null
        !error.startsWith('Target.scala:')
    }
}
