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

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Drives the compiler plugin through a real Gradle build, because how Gradle turns
 * {@code scalaCompilerPlugins} into {@code -Xplugin} arguments decides whether the plugin
 * can be published thin.
 *
 * <p>{@code -Xplugin} is a "load a plugin from each classpath" setting: dotty builds one
 * classloader per argument over the paths in that argument, and that loader does not see
 * the compile classpath. A thin jar therefore depends entirely on whether the build tool
 * emits one {@code -Xplugin} argument holding the whole classpath, or one per file.</p>
 *
 * <p>These tests answer that for Gradle: <strong>one per file</strong>. A jar carrying only
 * this plugin's classes fails with {@code NoClassDefFoundError} even when every dependency
 * is declared in {@code scalaCompilerPlugins} beside it, so the published artifact cannot be
 * thin for Gradle consumers.</p>
 *
 * <p>No repositories: every dependency is passed to the generated project as a file, so the
 * test is hermetic and does not resolve anything.</p>
 */
class ScalaGradlePluginFunctionalSpec extends Specification {

    @TempDir
    Path projectDir

    private static List<String> jarsOf(String property) {
        String value = System.getProperty(property)
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("System property [$property] is missing")
        }
        value.split(File.pathSeparator).findAll { it.endsWith('.jar') }
    }

    private static String pluginJar() {
        String jar = System.getProperty('micronaut.scala.plugin.jar')
        if (jar == null) {
            throw new IllegalStateException('System property [micronaut.scala.plugin.jar] is missing')
        }
        jar
    }

    /** The jars a generated project compiles against: the harness classpath, minus its own outputs. */
    private static List<String> compileJars() {
        jarsOf('micronaut.scala.test.classpath')
    }

    private static String fileList(Collection<String> paths) {
        paths.collect { "'" + it.replace('\\', '\\\\') + "'" }.join(', ')
    }

    private void writeProject(Collection<String> pluginFiles) {
        projectDir.resolve('settings.gradle').toFile().text = "rootProject.name = 'consumer'\n"
        projectDir.resolve('build.gradle').toFile().text = """
plugins {
    id 'scala'
}

// Needed for the Zinc compiler Gradle resolves for itself; the build runs --offline so
// everything comes from the shared cache rather than the network.
repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}


dependencies {
    implementation files(${fileList(compileJars())})
    scalaCompilerPlugins files(${fileList(pluginFiles)})
}

// Records what Gradle actually handed the compiler, which is the thing under test.
tasks.withType(ScalaCompile).configureEach {
    // The plugin targets the JDK 25 baseline. Without a release Gradle infers Java 8 for
    // the generated project and dotty rejects the output version outright.
    options.release = 25
    scalaCompileOptions.additionalParameters = (scalaCompileOptions.additionalParameters ?: []) +
            ['-J--add-modules=java.compiler']
    doFirst {
        file("\$buildDir/plugin-files.txt").parentFile.mkdirs()
        file("\$buildDir/plugin-files.txt").text = scalaCompilerPlugins.files.join('\\n')
    }
}
"""
        Path source = projectDir.resolve('src/main/scala/demo')
        source.toFile().mkdirs()
        source.resolve('Demo.scala').toFile().text = '''
package demo

import jakarta.inject.Singleton

@Singleton
class Demo {
  def hi(): String = "hi"
}
'''
    }

    /**
     * Runs the repository's own Gradle wrapper against the generated project.
     *
     * <p>Deliberately the wrapper as a separate process rather than the TestKit runner:
     * TestKit brings Gradle's own Groovy onto the test classpath, which this repository's
     * Spock version refuses to run against, and a separate process is closer to what a user
     * actually does.</p>
     */
    private String run(Collection<String> pluginFiles) {
        writeProject(pluginFiles)
        return rerun()
    }

    /** Runs the build again over whatever is currently in the project directory. */
    private String rerun() {
        def wrapper = new File(System.getProperty('micronaut.scala.repository.root'), 'gradlew')
        def process = new ProcessBuilder(
                wrapper.absolutePath,
                '-p', projectDir.toFile().absolutePath,
                'compileScala',
                '--offline',
                '--stacktrace'
        ).redirectErrorStream(true).start()
        String output = process.inputStream.text
        int status = process.waitFor()
        if (status != 0) {
            throw new IllegalStateException("Consumer build failed:\n" + output)
        }
        output
    }

    void 'the published plugin generates a bean definition through a Gradle build'() {
        when:
        run([pluginJar()])

        then: 'the plugin ran, and the definition it writes is in the build output'
        projectDir.resolve('build/classes/scala/main/demo/$Demo$Definition.class').toFile().exists()
    }

    void 'the compiler plugin classpath is what the build declares'() {
        when: 'the plugin jar plus the jars it needs, as a thin publication would resolve'
        def pluginFiles = [pluginJar()] + jarsOf('micronaut.scala.plugin.runtimeClasspath')
        run(pluginFiles)

        then: 'every declared file reaches the compiler as a plugin path'
        def recorded = projectDir.resolve('build/plugin-files.txt').toFile().readLines()
        recorded.size() == pluginFiles.size()

        and: 'and the plugin still loads and runs with them alongside it'
        projectDir.resolve('build/classes/scala/main/demo/$Demo$Definition.class').toFile().exists()
    }
    /**
     * Repacks the published jar keeping only this plugin's own classes and descriptors, which
     * is what a thin publication would contain.
     */
    private String thinJar() {
        def source = new java.util.jar.JarFile(new File(pluginJar()))
        def target = projectDir.resolve('thin-plugin.jar').toFile()
        target.withOutputStream { out ->
            def jar = new java.util.jar.JarOutputStream(out)
            source.entries().each { entry ->
                boolean keep = entry.name == 'plugin.properties' ||
                        entry.name.startsWith('io/micronaut/scala/') ||
                        entry.name.startsWith('META-INF/services/')
                if (keep && !entry.isDirectory()) {
                    jar.putNextEntry(new java.util.zip.ZipEntry(entry.name))
                    source.getInputStream(entry).withStream { input -> jar << input }
                    jar.closeEntry()
                }
            }
            jar.finish()
        }
        source.close()
        target.absolutePath
    }

    void 'a thin plugin jar cannot load, even with its dependencies declared alongside it'() {
        given: 'the decisive case for packaging -- see D2 in SCALA3_REMEDIATION_PLAN.md'
        def thin = thinJar()
        def dependencies = jarsOf('micronaut.scala.plugin.runtimeClasspath')

        when: 'the plugin carries only its own classes, with every dependency declared too'
        run([thin] + dependencies)

        then: 'Gradle passes each file as its own -Xplugin, and dotty gives each argument its'
        def e = thrown(IllegalStateException)

        and: 'own classloader -- so the plugin cannot see the jars declared beside it'
        e.message.contains('NoClassDefFoundError')
        e.message.contains('io/micronaut/inject/processing/ProcessingException')
    }

    void 'a thin plugin jar on its own cannot load either'() {
        given:
        def thin = thinJar()

        when: 'here the dependencies are only on the compile classpath, which it cannot see'
        run([thin])

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('NoClassDefFoundError')
    }

    private java.nio.file.Path sourceDir() {
        projectDir.resolve('src/main/scala/demo')
    }

    private List<String> generatedDefinitions() {
        def classes = projectDir.resolve('build/classes/scala/main/demo').toFile()
        classes.exists() ? classes.list().findAll { it.endsWith('$Definition.class') }.sort() : []
    }

    private List<String> beanMarkers() {
        def markers = projectDir.resolve(
                'build/classes/scala/main/META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference').toFile()
        markers.exists() ? markers.list().sort() : []
    }

    void 'a bean whose source is deleted leaves its definition behind'() {
        given: 'two beans, compiled together'
        writeProject([pluginJar()])
        sourceDir().resolve('Second.scala').toFile().text = '''
package demo

import jakarta.inject.Singleton

@Singleton
class Second {
  def hi(): String = "second"
}
'''
        rerun()

        expect:
        generatedDefinitions() == ['$Demo$Definition.class', '$Second$Definition.class']
        beanMarkers().size() == 2

        when: 'one source is deleted and the project is rebuilt'
        sourceDir().resolve('Second.scala').toFile().delete()
        rerun()

        then: 'the definition and its marker are still there'
        // Bean definitions are written straight into the class output directory, so the
        // build tool never records them as products of the source that produced them and
        // cannot clean them when it goes. The stale definition is loadable, and its
        // marker still advertises it, so the deleted bean is still a bean.
        generatedDefinitions().contains('$Second$Definition.class')
        beanMarkers().any { it.contains('Second') }
    }

    void 'each bean gets its own marker file rather than a shared service file'() {
        given:
        writeProject([pluginJar()])
        sourceDir().resolve('Second.scala').toFile().text = '''
package demo

import jakarta.inject.Singleton

@Singleton
class Second
'''
        rerun()

        expect: 'one empty file per implementation, named for the class'
        beanMarkers() == ['demo.$Demo$Definition', 'demo.$Second$Definition']

        and: 'so a partial recompile cannot truncate the others, which a single'
        beanMarkers().every {
            projectDir.resolve(
                'build/classes/scala/main/META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference'
            ).resolve(it).toFile().length() == 0
        }
    }

}
