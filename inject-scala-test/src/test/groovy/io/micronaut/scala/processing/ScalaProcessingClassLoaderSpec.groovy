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

import io.micronaut.inject.visitor.TypeElementVisitor
import io.micronaut.scala.processing.visitor.ScalaProcessingClassLoader
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path

/**
 * The classloader a compilation's visitors run in, and what it does when a visitor's dependency
 * is also reachable through the plugin's parent chain -- which, under Gradle, ends at a loader
 * built from the whole {@code scalaClasspath}, scaladoc's Jackson and snakeyaml included.
 */
class ScalaProcessingClassLoaderSpec extends Specification {

    @TempDir
    @Shared
    Path workDirectory

    /** Every class of the {@code split} package, as the compilation classpath has it. */
    @Shared
    Path classpath

    /** Only the package-private half, as a compiler loader that has already served it. */
    @Shared
    Path partialParent

    /** A plugin that bundles nothing. */
    @Shared
    URL emptyPlugin

    /** The plugin under test, which bundles Micronaut and ASM. */
    @Shared
    URL plugin

    def setupSpec() {
        classpath = compileSplitPackage(workDirectory.resolve('classpath'))
        partialParent = Files.createDirectories(workDirectory.resolve('parent/split'))
        Files.copy(classpath.resolve('split/Builder.class'), partialParent.resolve('Builder.class'))
        partialParent = partialParent.parent
        Files.createDirectories(partialParent.resolve('javax/vendor'))
        Files.copy(classpath.resolve('javax/vendor/Marker.class'), partialParent.resolve('javax/vendor/Marker.class'))
        emptyPlugin = Files.createDirectories(workDirectory.resolve('empty-plugin')).toUri().toURL()
        plugin = new File(System.getProperty('micronaut.scala.plugin.jar')).toURI().toURL()
    }

    void "a parent-first loader splits a package between the compiler and the compilation classpath"() {
        given:
        def parent = new URLClassLoader([partialParent.toUri().toURL()] as URL[], ClassLoader.platformClassLoader)
        def loader = new URLClassLoader([classpath.toUri().toURL()] as URL[], parent)

        when:
        loader.loadClass('split.Settings').getMethod('builder').invoke(null)

        then:
        def e = thrown(java.lang.reflect.InvocationTargetException)
        e.cause instanceof IllegalAccessError
        e.cause.message.contains('split.Settings tried to access method')
    }

    void "the processing loader takes the compilation classpath first, so a package never splits"() {
        given:
        def parent = new URLClassLoader([partialParent.toUri().toURL()] as URL[], ClassLoader.platformClassLoader)
        def loader = new ScalaProcessingClassLoader([classpath.toUri().toURL()] as URL[], parent, emptyPlugin)

        when:
        def settings = loader.loadClass('split.Settings')
        def builder = settings.getMethod('builder').invoke(null)

        then:
        settings.classLoader.is(loader)
        builder.getClass().classLoader.is(loader)

        and: 'a javax package the parent also has is not the JDK because of its name'
        loader.loadClass('javax.vendor.Marker').classLoader.is(loader)
        Path.of(loader.getResource('split/Builder.class').toURI()) == classpath.resolve('split/Builder.class')
        loader.getResources('split/Builder.class').toList().collect { Path.of(it.toURI()) } == [
            classpath.resolve('split/Builder.class'),
            partialParent.resolve('split/Builder.class')
        ]
    }

    void "what a visitor shares with the plugin is still the plugin's"() {
        given:
        // The test classpath stands in for both: the parent, as the plugin's loader, and the
        // compilation classpath, which under Gradle carries its own copy of everything a visitor
        // depends on.
        def parent = getClass().classLoader
        def loader = new ScalaProcessingClassLoader(testClasspath(), parent, plugin)

        expect: 'the JDK and the compiler'
        loader.loadClass('java.util.List').is(List)
        loader.loadClass('javax.lang.model.element.Modifier').is(javax.lang.model.element.Modifier)
        loader.loadClass('scala.Option').is(parent.loadClass('scala.Option'))

        and: 'the Element API and what the plugin bundles with it'
        loader.loadClass(TypeElementVisitor.name).is(TypeElementVisitor)
        loader.loadClass('org.objectweb.asm.ClassWriter').is(parent.loadClass('org.objectweb.asm.ClassWriter'))
        loader.getResource(TypeElementVisitor.name.replace('.', '/') + '.class') ==
            parent.getResource(TypeElementVisitor.name.replace('.', '/') + '.class')

        and: 'the harness, which shares its visitors with the tests that drive them'
        loader.loadClass('io.micronaut.scala.processing.test.ScalaCompiler').classLoader.is(parent)

        and: 'a Micronaut module the plugin does not bundle is a visitor like any other'
        loader.loadClass('io.micronaut.validation.visitor.ValidationVisitor').classLoader.is(loader)
        loader.loadClass('jakarta.validation.constraints.Digits').classLoader.is(loader)

        and: 'so is anything else the parent happens to be able to see'
        loader.loadClass('spock.lang.Specification').classLoader.is(loader)
    }

    void "a class only the parent has is still found"() {
        given:
        def parent = new URLClassLoader([partialParent.toUri().toURL()] as URL[], ClassLoader.platformClassLoader)
        def loader = new ScalaProcessingClassLoader([workDirectory.resolve('empty').toUri().toURL()] as URL[], parent, emptyPlugin)

        expect:
        loader.loadClass('split.Builder').classLoader.is(parent)

        when:
        loader.loadClass('split.Missing')

        then:
        thrown(ClassNotFoundException)
    }

    private static URL[] testClasspath() {
        System.getProperty('micronaut.scala.test.classpath').split(File.pathSeparator)
            .collect { new File(it).toURI().toURL() } as URL[]
    }

    /**
     * Two public classes in one package, one of them calling the other's package-private
     * constructor: the shape of {@code LoadSettings.builder()} in snakeyaml-engine.
     */
    private static Path compileSplitPackage(Path output) {
        Path sources = Files.createDirectories(output.resolveSibling('sources/split'))
        Files.writeString(sources.resolve('Settings.java'), '''
package split;
public class Settings {
    public static Builder builder() {
        return new Builder();
    }
}
''')
        Files.writeString(sources.resolve('Builder.java'), '''
package split;
public class Builder {
    Builder() {
    }
}
''')
        // A library that happens to live under javax, as javax.inject and javax.annotation do.
        Path vendor = Files.createDirectories(sources.resolveSibling('vendor'))
        Files.writeString(vendor.resolve('Marker.java'), '''
package javax.vendor;
public class Marker {
}
''')
        Files.createDirectories(output)
        def compiler = ToolProvider.systemJavaCompiler
        int result = compiler.run(
            null, null, null,
            '-d', output.toString(),
            sources.resolve('Settings.java').toString(),
            sources.resolve('Builder.java').toString(),
            vendor.resolve('Marker.java').toString()
        )
        assert result == 0
        output
    }
}
