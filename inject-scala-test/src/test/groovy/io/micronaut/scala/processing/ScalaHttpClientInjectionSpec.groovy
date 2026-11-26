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

import io.micronaut.http.HttpStatus
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.runtime.server.EmbeddedServer
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * Regression for #19: client configuration must reach the generated setter's argument.
 */
class ScalaHttpClientInjectionSpec extends AbstractScalaTypeElementSpec {

    void "retains client configuration on an injected Scala var with #target annotations"() {
        given:
        def context = buildContext('''
package clientinjection

import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import jakarta.inject.{Inject, Singleton}
import scala.compiletime.uninitialized
import scala.annotation.meta.field

@Singleton
class ClientHolder:
  @Inject
  @CLIENT@
  var client: HttpClient = uninitialized

  def requestStatus(): Int = client.toBlocking.exchange("/", classOf[String]).code()
'''.replace('@CLIENT@', clientAnnotation), ['micronaut.server.port': -1], true)
        def server = context.getBean(EmbeddedServer).start()

        when:
        def definition = getBeanDefinition(context, 'clientinjection.ClientHolder')
        def argument = definition.injectedMethods.find { it.name == 'client_$eq' }.arguments[0]

        then: 'the argument retains the annotation even though Client is not a qualifier'
        argument.annotationMetadata.stringValue(Client).get() == '/'

        when:
        getBean(context, 'clientinjection.ClientHolder').requestStatus()

        then: 'the server responds with 404 because this context deliberately declares no routes'
        def response = thrown(HttpClientResponseException)
        response.status == HttpStatus.NOT_FOUND

        cleanup:
        server?.close()
        context?.close()

        where:
        target   | clientAnnotation
        'plain'  | '@Client("/")'
        'field'  | '@(Client @field)("/")'
    }
}
