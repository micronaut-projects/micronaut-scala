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

import dotty.tools.dotc.Main
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Measures what the compiler plugin adds to a compilation.
 *
 * <p>Every compilation here runs in this JVM through the same entry point the harness uses,
 * so the two configurations differ only in {@code -Xplugin}. Each workload is compiled
 * several times in each configuration after warm-up, and the medians are reported, since a
 * single compile in a fresh JVM measures JIT warm-up more than it measures anything else. A
 * JFR recording of the plugin runs is summarised by the plugin's own frames, so the report
 * says where the overhead is and not only how much there is.</p>
 *
 * <p>Two workloads. The guide examples are real Micronaut code of every kind -- beans, AOP,
 * configuration, introspections, introduction advice. The synthetic application is what a
 * large project looks like to the plugin: many beans referring to many JDK and Micronaut
 * types, which is exactly what a model of classpath types built from the compiler's symbols
 * has to pay for.</p>
 */
class ScalaCompilerBenchmark extends Specification {

    private static final int WARMUP = 2

    void "report the plugin's share of compile time"() {
        given:
        int iterations = Integer.getInteger('micronaut.scala.benchmark.iterations', 5)
        Path output = Path.of(System.getProperty('micronaut.scala.benchmark.output'))
        Files.createDirectories(output)
        def workloads = [
            examples : exampleSources(),
            synthetic: syntheticSources(100),
        ]
        def report = new StringBuilder()

        when:
        workloads.each { name, sources ->
            def work = Files.createTempDirectory("benchmark-$name")
            def files = writeSources(work.resolve('src'), sources)
            (1..WARMUP).each { compile(files, work.resolve("warm-plain-$it"), false); compile(files, work.resolve("warm-plugin-$it"), true) }
            List<Long> plain = (1..iterations).collect { compile(files, work.resolve("plain-$it"), false) }
            long retainedBefore = retainedHeapMb()
            List<Long> plugin = (1..iterations).collect { compile(files, work.resolve("plugin-$it"), true) }
            long retainedAfter = retainedHeapMb()
            long plainMedian = median(plain), pluginMedian = median(plugin)
            report << String.format('%-10s %4d files  plain %6d ms  plugin %6d ms  overhead %+6d ms (%+.0f%%)%n',
                name, files.size(), plainMedian, pluginMedian, pluginMedian - plainMedian,
                100.0 * (pluginMedian - plainMedian) / plainMedian)
            report << "           plain runs  ${plain}\n           plugin runs ${plugin}\n"
            report << String.format('           retained heap after GC: %d MB before the plugin runs, %d MB after %d of them (%+d MB)%n',
                retainedBefore, retainedAfter, iterations, retainedAfter - retainedBefore)
            System.err.println("benchmark: ${name} done")
        }

        and: 'a profile of the plugin runs on the synthetic workload'
        def work = Files.createTempDirectory('benchmark-profile')
        def files = writeSources(work.resolve('src'), workloads.synthetic)
        Path jfr = output.resolve('plugin.jfr')
        try (Recording recording = new Recording()) {
            recording.enable('jdk.ExecutionSample').withPeriod(java.time.Duration.ofMillis(5))
            recording.start()
            (1..3).each { compile(files, work.resolve("profile-$it"), true) }
            recording.stop()
            recording.dump(jfr)
        }
        report << '\n' << profile(jfr, 25)
        Files.writeString(output.resolve('report.txt'), report.toString())
        println "\nCOMPILER BENCHMARK\n${report}\nreport: ${output.resolve('report.txt')}\nprofile: ${jfr}"

        then:
        true
    }

    /** Wall time of one compilation, in milliseconds. */
    private static long compile(List<Path> sources, Path outputDirectory, boolean withPlugin) {
        Files.createDirectories(outputDirectory)
        List<String> arguments = ['-classpath', System.getProperty('micronaut.scala.test.classpath'),
                                  '-d', outputDirectory.toString(), '-release:25']
        if (withPlugin) {
            arguments << "-Xplugin:${System.getProperty('micronaut.scala.plugin.jar')}".toString()
        }
        arguments.addAll(sources*.toString())
        long start = System.nanoTime()
        def reporter = Main.process(arguments as String[])
        long elapsed = (System.nanoTime() - start).intdiv(1_000_000)
        System.err.println("benchmark: compiled ${sources.size()} files ${withPlugin ? 'with' : 'without'} plugin in ${elapsed} ms")
        if (reporter.hasErrors()) {
            throw new IllegalStateException("Compilation failed: ${reporter.summary()}")
        }
        elapsed
    }

    /** Heap still in use after a full collection, in megabytes: what a compilation left behind. */
    private static long retainedHeapMb() {
        (1..3).each { System.gc(); Thread.sleep(100) }
        Runtime runtime = Runtime.getRuntime()
        (runtime.totalMemory() - runtime.freeMemory()).intdiv(1024 * 1024)
    }

    private static long median(List<Long> values) {
        def sorted = values.sort(false)
        sorted[sorted.size().intdiv(2)]
    }

    private static List<Path> writeSources(Path directory, Map<String, String> sources) {
        Files.createDirectories(directory)
        sources.collect { name, text ->
            Path file = directory.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
            file
        }
    }

    private static Map<String, String> exampleSources() {
        Path root = Path.of(System.getProperty('micronaut.scala.benchmark.examples'))
        def sources = [:]
        Files.walk(root).withCloseable { stream ->
            // The serialization example needs micronaut-serde, which the harness classpath does not carry.
            stream.filter { it.toString().endsWith('.scala') && !it.toString().contains('/serialization/') }
                .forEach { sources[root.relativize(it).toString()] = Files.readString(it) }
        }
        sources
    }

    /**
     * A synthetic application: a chain of singletons injecting one another, each with a
     * configuration class and an introspected case class over JDK and Micronaut types.
     */
    private static Map<String, String> syntheticSources(int count) {
        def sources = [:]
        (0..<count).each { i ->
            def dependency = i == 0 ? '' : "dep: Bean${i - 1}, "
            sources["synthetic/Bean${i}.scala".toString()] = """
package synthetic

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.convert.ConversionService
import jakarta.inject.Singleton

import java.time.Duration
import java.util.Optional

@Singleton
class Bean${i}(${dependency}conversion: ConversionService, config: Config${i}):
  def describe(input: String, count: Int): Optional[String] = Optional.of(input * count)
  def names: java.util.List[String] = java.util.List.of(config.name)
  def timeout: Duration = config.timeout.getOrElse(Duration.ZERO)
  def lookup(keys: java.util.Map[String, java.lang.Integer]): Map[String, Int] = Map.empty
  def when(now: java.time.Instant, at: java.time.LocalDate): java.time.Instant = now

@ConfigurationProperties("synthetic.bean${i}")
case class Config${i}(name: String, timeout: Option[Duration], tags: List[String] = Nil, retries: Int = 3)

@Introspected
case class Record${i}(id: java.util.UUID, label: String, weight: Double, created: java.time.Instant, parents: java.util.List[String])
"""
        }
        sources
    }

    /** The hottest frames of the plugin's own code, with the share of samples that pass through them. */
    private static String profile(Path jfr, int top) {
        Map<String, Integer> inclusive = [:]
        Map<String, Integer> selfTop = [:]
        int samples = 0, inPlugin = 0
        RecordingFile.readAllEvents(jfr).each { event ->
            if (event.eventType.name != 'jdk.ExecutionSample') {
                return
            }
            samples++
            def frames = event.stackTrace.frames*.method.collect { "${it.type.name}.${it.name}".toString() }
            def ours = frames.findAll { it.startsWith('io.micronaut.scala.') }
            if (ours) {
                inPlugin++
                ours.unique().each { inclusive[it] = (inclusive[it] ?: 0) + 1 }
                // The plugin frame nearest the top of the stack is where its own time goes.
                selfTop[ours[0]] = (selfTop[ours[0]] ?: 0) + 1
            }
        }
        def text = new StringBuilder()
        text << String.format('Profile: %d samples, %d (%.0f%%) inside the plugin%n', samples, inPlugin, samples == 0 ? 0 : 100.0 * inPlugin / samples)
        text << "\nPlugin frames nearest the top of the stack (where the plugin's time is spent):\n"
        selfTop.sort { -it.value }.take(top).each { frame, n -> text << String.format('  %5.1f%%  %s%n', 100.0 * n / Math.max(inPlugin, 1), frame) }
        text << "\nPlugin frames by inclusive samples:\n"
        inclusive.sort { -it.value }.take(top).each { frame, n -> text << String.format('  %5.1f%%  %s%n', 100.0 * n / Math.max(inPlugin, 1), frame) }
        text.toString()
    }
}
