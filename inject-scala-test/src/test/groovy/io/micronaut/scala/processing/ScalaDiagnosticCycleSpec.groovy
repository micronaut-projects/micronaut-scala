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
import io.micronaut.scala.processing.test.ScalaCyclicFailureVisitor
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Reporting a failure walks the cause chain for the first usable message. The walk
 * compared each cause only against the head of the chain, which catches a two-element
 * cycle and nothing deeper: for {@code a -> b -> c -> b} the walk never returns to
 * {@code a}, so it ran forever and the compiler hung while producing a diagnostic.
 */
class ScalaDiagnosticCycleSpec extends AbstractScalaTypeElementSpec {

    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void 'a cyclic cause chain is reported rather than hanging the compiler'() {
        when:
        ScalaCyclicFailureVisitor.failing {
            buildClassLoader('probe.X', 'package probe\n\nclass X\n')
        }

        then: 'the compilation fails, and it fails by terminating'
        def e = thrown(Exception)
        e.message.contains('Error initializing type visitor')
    }
}
