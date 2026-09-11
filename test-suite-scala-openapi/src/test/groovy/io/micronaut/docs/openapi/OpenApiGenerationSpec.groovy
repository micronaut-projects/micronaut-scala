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
package io.micronaut.docs.openapi

import spock.lang.Specification

/**
 * Asserts the OpenAPI definition micronaut-openapi generates from Scala sources.
 *
 * <p>micronaut-openapi is an ordinary set of {@code TypeElementVisitor}s, so it runs in the
 * Scala pipeline without knowing Scala exists. That is exactly why it is worth a test of its
 * own: it exercises the element model the way a third-party visitor does rather than the way
 * this repository's own tests do, and it is a real build -- forked scalac, the plugin loaded
 * from its jar, the specification written to disk -- rather than the in-process harness.</p>
 *
 * <p>That distinction is not academic. The gap this module was written to close only appeared
 * in a real build: the in-process harness happens to have every annotation on the JVM classpath
 * that the plugin's own classloader reads, so {@code getAnnotationTypeByStereotype} resolved
 * there and returned empty under Gradle, and micronaut-openapi read that as "not an endpoint"
 * and wrote a specification containing nothing but its title.</p>
 */
class OpenApiGenerationSpec extends Specification {

    private static String specification() {
        // Every Micronaut jar ships one of these; the generated one is the copy written into
        // this module's own compiled output.
        def url = OpenApiGenerationSpec.classLoader
            .getResources('META-INF/swagger/service-1.0.0.yml')
            .toList()
            .find { it.text.contains('/pets') }
        url?.text
    }

    void "the controller becomes a documented path"() {
        given:
        def spec = specification()

        expect: 'the specification exists at all, which is what the visitors having run means'
        spec != null

        and: 'the path combines the class template with the method template'
        spec.contains('/pets/{name}:')

        and: 'and carries the HTTP method the annotation names'
        spec.contains('get:')
        spec.contains('operationId: find')
    }

    void "the path parameter is described from the method signature"() {
        given:
        def spec = specification()

        expect:
        spec.contains('- name: name')
        spec.contains('in: path')
        spec.contains('required: true')
    }

    void "the introspected return type becomes a component schema"() {
        given: '''Pet is reachable only through the introspection the plugin generates, so this
                  asserts the introspection is complete enough for a schema to be built from it'''
        def spec = specification()

        expect: 'the response refers to a schema rather than inlining an untyped object'
        spec.contains('$ref: "#/components/schemas/Pet"')

        and: 'and the schema has both properties, at their Scala types'
        spec.contains('Pet:')
        spec.contains('name:') && spec.contains('type: string')
        spec.contains('age:') && spec.contains('format: int32')
    }

    void "Scaladoc becomes the summary and the schema description"() {
        given: '''the doc comments are read by micronaut-openapi's own Javadoc parser, which
                  expects the comment's content rather than the raw comment -- returning the
                  delimiters put a literal "/**" at the front of every summary'''
        def spec = specification()

        expect:
        spec.contains('summary: Finds a pet by name.')
        spec.contains('description: A pet.')

        and: 'nothing leaks the comment markers'
        !spec.contains('/**')
        !spec.contains('*/')
    }

    void "a JSON schema is generated from an annotated case class"() {
        given: '''micronaut-json-schema is a second, independent consumer of the same element
                  model, so it is worth asserting separately rather than assuming openapi
                  passing means every generator does'''
        def schema = OpenApiGenerationSpec.classLoader
            .getResource('META-INF/schemas/invoice.schema.json')?.text

        expect:
        schema != null

        and: 'the case class parameters are the schema properties, at their Scala types'
        schema.contains('"title": "Invoice"') || schema.contains('"title":"Invoice"')
        schema.contains('"reference"')
        schema.contains('"amount"')
        schema.contains('"string"')
        schema.contains('"integer"')

        and: 'and the class documentation is the schema description'
        schema.contains('A customer invoice.')
    }
}
