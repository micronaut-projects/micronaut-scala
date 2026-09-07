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
import io.micronaut.scala.processing.test.ScalaVisitorOrderRecorder
import io.micronaut.scala.processing.visitor.ScalaProcessingEngine

import java.nio.file.Files
import java.util.function.BiConsumer

/**
 * Bean definitions must never be generated without the type visitors having run.
 * Generating them first produces beans with no AOP, no introspections, no validation and
 * no user visitors -- and reports nothing at all, so the damage is silent.
 *
 * This is asserted against the engine rather than through a compilation because the
 * ordering used to depend on a unit-count heuristic in the phase wiring, and the point of
 * the fix is that no caller can get the order wrong any more.
 */
class ScalaPipelineOrderingSpec extends AbstractScalaTypeElementSpec {

    void 'generating bean definitions runs the type visitors first'() {
        given:
        def outputDirectory = Files.createTempDirectory('micronaut-scala-pipeline').toFile()
        def classpath = System.getProperty('micronaut.scala.test.classpath')
                .split(File.pathSeparator)
                .findAll { it }
                .collect { new File(it) }
        def noop = { String message, Object element -> } as BiConsumer<String, Object>

        when: 'bean definition generation is driven without processTypeVisitors() ever being called'
        def events = ScalaVisitorOrderRecorder.withRecording {
            def engine = new ScalaProcessingEngine(outputDirectory, classpath, [:], noop, noop, noop)
            engine.processBeanDefinitions()
            ScalaVisitorOrderRecorder.events()
        }

        then: 'the visitor pass ran anyway'
        events.contains('high-start')
        events.contains('high-finish')

        cleanup:
        outputDirectory.deleteDir()
    }

    void 'the type visitor pass runs only once'() {
        given:
        def outputDirectory = Files.createTempDirectory('micronaut-scala-pipeline').toFile()
        def classpath = System.getProperty('micronaut.scala.test.classpath')
                .split(File.pathSeparator)
                .findAll { it }
                .collect { new File(it) }
        def noop = { String message, Object element -> } as BiConsumer<String, Object>

        when: 'the visitor pass is requested explicitly and then again through generation'
        def events = ScalaVisitorOrderRecorder.withRecording {
            def engine = new ScalaProcessingEngine(outputDirectory, classpath, [:], noop, noop, noop)
            engine.processTypeVisitors()
            engine.processBeanDefinitions()
            ScalaVisitorOrderRecorder.events()
        }

        then: 'visitors are not started twice'
        events.count { it == 'high-start' } == 1
        events.count { it == 'high-finish' } == 1

        cleanup:
        outputDirectory.deleteDir()
    }
}
