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

import io.micronaut.inject.visitor.VisitorContext
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler

import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two compilations in one JVM at the same time, which is what a Gradle compiler daemon does
 * with two projects, leave the JVM as they found it and each produce their own beans.
 *
 * <p>Processing sets JVM-global state -- the {@code micronaut.*} options and the switches that
 * make core load through the context class loader are system properties -- and restores it
 * afterwards. Two compilations doing that at once used to interleave: the second saw the
 * first's {@code true} as the value to restore and left it set, and the first cleared the
 * property while the second was still processing under it, at which point core stopped
 * loading through the second compilation's class loader halfway through its visitors.</p>
 */
class ScalaConcurrentCompilationSpec extends AbstractScalaTypeElementSpec {

    private static final String INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = 'micronaut.introspections.use.context.classloader'

    void "concurrent compilations each produce their beans and leave no processing state behind"() {
        given:
        System.clearProperty(VisitorContext.MICRONAUT_PROCESSING_USE_CONTEXT_CLASSLOADER)
        System.clearProperty(INTROSPECTIONS_USE_CONTEXT_CLASSLOADER)
        def executor = Executors.newFixedThreadPool(2)
        def leaked = []

        when: 'several rounds, since the interleaving is a matter of timing'
        def rounds = (1..6).collect { round ->
            def barrier = new CyclicBarrier(2)
            def futures = ['a', 'b'].collect { side ->
                executor.submit({
                    barrier.await(30, TimeUnit.SECONDS)
                    def loader = buildClassLoader("${side}.Bean${round}", """
package ${side}

import jakarta.inject.Singleton

@Singleton
class Bean${round}:
  def name: String = "${side}"
""")
                    loader.loadClass("${side}.\$Bean${round}\$Definition") != null
                } as Callable<Boolean>)
            }
            def results = futures.collect { it.get(5, TimeUnit.MINUTES) }
            [VisitorContext.MICRONAUT_PROCESSING_USE_CONTEXT_CLASSLOADER, INTROSPECTIONS_USE_CONTEXT_CLASSLOADER].each {
                if (System.getProperty(it) != null) {
                    leaked << "round $round: $it=${System.getProperty(it)}"
                    System.clearProperty(it)
                }
            }
            results
        }

        then: 'every compilation generated its definition'
        rounds.every { it == [true, true] }

        and: 'and none left a property behind for the next compilation in the JVM'
        leaked == []

        and: '''the system properties are still a String-to-String map, which is their contract
                and what Zinc reads them as while setting up the next compilation in the same
                daemon: the lock the compilations share used to be an Object stored among them'''
        System.getProperties().entrySet().findAll { !(it.key instanceof String) || !(it.value instanceof String) }.empty

        cleanup:
        executor.shutdownNow()
    }
}
