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

class ScalaConstructorCopySpec extends AbstractScalaTypeElementSpec {

    void "builds introspection for Scala constructor forwarding through abstract superclass"() {
        when:
        def introspection = buildBeanIntrospection('concopy.SubscribeMessage', '''
package concopy

import io.micronaut.core.annotation.Introspected

sealed abstract class Message:
  def messageType: String

abstract class RequiredPayloadMessage[T](payloadValue: T) extends Message:
  def payload: T = payloadValue

@Introspected
final class SubscribeMessage(val id: String, override val payload: SubscribeMessage.SubscribePayload)
    extends RequiredPayloadMessage[SubscribeMessage.SubscribePayload](payload):
  override def messageType: String = "subscribe"

object SubscribeMessage:
  @Introspected
  final class SubscribePayload(val query: String)
''')
        def payloadType = introspection.beanType.classLoader.loadClass('concopy.SubscribeMessage$SubscribePayload')
        def payload = payloadType.getConstructor(String).newInstance('query')
        def bean = introspection.instantiate('1', payload)

        then:
        noExceptionThrown()
        introspection.constructorArguments*.name == ['id', 'payload']
        bean.id() == '1'
        bean.payload().is(payload)
    }
}
