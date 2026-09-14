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

/**
 * P3 parity, ported from {@code inject-java}'s {@code ConfigurationMetadataSpec}.
 *
 * <p>A configuration class produces a metadata document alongside its bean definition, which is
 * what an IDE completes property names from. It is written as a side effect and read by nothing
 * at runtime, so nothing else in this suite would notice if it stopped being produced or lost a
 * property.</p>
 *
 * <p>The property paths in it are composed the same way binding composes them, so it is also a
 * second reading of the prefix rules -- from the document rather than from a bound value.</p>
 */
class ScalaConfigurationMetadataParitySpec extends AbstractScalaTypeElementSpec {

    private static final String METADATA = 'META-INF/spring-configuration-metadata.json'

    /**
     * Reads the document this compilation produced, not one the classpath already carries.
     *
     * <p>Micronaut's own modules ship a {@code spring-configuration-metadata.json}, and a
     * URLClassLoader finds the parent's first -- a probe through {@code getResourceAsStream}
     * reported success while reading micronaut-http's file.</p>
     */
    private static String metadata(ClassLoader classLoader) {
        def output = new File(((URLClassLoader) classLoader).URLs[0].toURI())
        def file = new File(output, METADATA)
        file.isFile() ? file.text : null
    }

    void "writes a metadata entry for each configuration property"() {
        when:
        def classLoader = buildClassLoader('configmeta.AppConfig', '''
package configmeta

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
class AppConfig:
  var name: String = null
  var port: Int = 0
''')
        def document = metadata(classLoader)

        then: 'the group is the prefix, and each property is named under it'
        document.contains('"app.name"')
        document.contains('"app.port"')

        and: 'with the declared types, which is what a completion would show'
        document.contains('java.lang.String')
    }

    void "composes a nested prefix in the metadata as binding composes it"() {
        when:
        def classLoader = buildClassLoader('configmeta.ChildConfig', '''
package configmeta

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("parent")
class ParentConfig:
  var shared: String = null

@ConfigurationProperties("child")
class ChildConfig extends ParentConfig:
  var own: String = null
''')
        def document = metadata(classLoader)

        then: 'the subclass property is recorded under the composed prefix'
        document.contains('"parent.child.own"')
    }

    void "writes metadata for constructor-bound properties"() {
        when: 'the immutable form, where the properties are constructor parameters'
        def classLoader = buildClassLoader('configmeta.ImmutableConfig', '''
package configmeta

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("immutable")
class ImmutableConfig(val host: String, val port: Int)
''')
        def document = metadata(classLoader)

        then: 'a constructor parameter is as much a documented property as a var is'
        document.contains('"immutable.host"')
        document.contains('"immutable.port"')
    }
}
