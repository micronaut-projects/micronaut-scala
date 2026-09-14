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
 * P3 parity, ported from {@code inject-java}'s {@code ConfigurationJsonSchemaSpec},
 * {@code ConfigurationJsonSchemaDefaultsSpec} and
 * {@code ConfigurationJsonSchemaValidationSpec}.
 *
 * <p>A configuration class produces a JSON schema beside its metadata document, one file per
 * class under {@code META-INF/micronaut-configuration-schemas}. It describes the same properties
 * the metadata does, in the form a tool validating a configuration file consumes, and like the
 * metadata it is written as a side effect that nothing at runtime reads.</p>
 */
class ScalaConfigurationSchemaParitySpec extends AbstractScalaTypeElementSpec {

    // groovy-json is not on this module's test classpath, and the assertions here are about
    // which names a schema mentions rather than about its structure, so the text is matched
    // directly instead of parsed.
    private static String schemaFor(ClassLoader classLoader, String className) {
        def output = new File(((URLClassLoader) classLoader).URLs[0].toURI())
        def file = new File(output, "META-INF/micronaut-configuration-schemas/${className}.json")
        file.isFile() ? file.text : null
    }

    void "writes a schema describing a configuration class's properties"() {
        when:
        def classLoader = buildClassLoader('configschema.MyProps', '''
package configschema

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("foo.bar")
class MyProps:
  var host: String = null
  var port: Int = 0
''')
        def schema = schemaFor(classLoader, 'configschema.MyProps')

        then: 'a schema is produced for the class, against the draft core writes'
        schema != null
        // The writer escapes forward slashes, so the URL is matched by its host and draft
        // rather than as one literal.
        schema.contains('json-schema.org')
        schema.contains('2020-12')

        and: 'describing the same properties the metadata records, with their bound paths'
        schema.contains('"foo.bar.host"')
        schema.contains('"foo.bar.port"')

        and: 'and the Scala types mapped to their JSON kinds'
        schema.contains('"type":"string"')
        schema.contains('"type":"integer"')
    }

    void "describes a constructor-bound configuration class"() {
        when: 'the immutable form, where the properties are constructor parameters'
        def classLoader = buildClassLoader('configschema.Immutable', '''
package configschema

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("immutable")
class Immutable(val host: String, val port: Int)
''')
        def schema = schemaFor(classLoader, 'configschema.Immutable')

        then: 'a constructor parameter is described like a var is'
        schema != null
        schema.contains('"host"')
        schema.contains('"port"')
    }
}
