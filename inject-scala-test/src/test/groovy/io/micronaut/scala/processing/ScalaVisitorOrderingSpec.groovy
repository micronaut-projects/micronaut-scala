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

import java.util.function.Supplier

class ScalaVisitorOrderingSpec extends AbstractScalaTypeElementSpec {

    void "orders Scala type element visitors by getOrder"() {
        when:
        def events = ScalaVisitorOrderRecorder.withRecording({
            buildClassElement('visitororder.OrderedBean', '''
package visitororder

class OrderedBean:
  def run(): String = "ok"
''')
            ScalaVisitorOrderRecorder.events()
        } as Supplier)

        then:
        events == [
            'high-start',
            'low-start',
            'high-class:visitororder.OrderedBean',
            'low-class:visitororder.OrderedBean',
            'high-finish',
            'low-finish'
        ]
    }
    void "dispatches class by class, not visitor by visitor"() {
        when: "two classes, so the two dispatch orders are distinguishable at all"
        def events = ScalaVisitorOrderRecorder.withRecording({
            buildClassLoader('visitororder.First', '''
package visitororder

class First:
  def run(): String = "1"

class Second:
  def run(): String = "2"
''')
            ScalaVisitorOrderRecorder.events()
        } as Supplier)

        then: "every visitor sees a class before any visitor sees the next one, as in inject-java"
        events == [
            'high-start',
            'low-start',
            'high-class:visitororder.First',
            'low-class:visitororder.First',
            'high-class:visitororder.Second',
            'low-class:visitororder.Second',
            'high-finish',
            'low-finish'
        ]
    }

}
