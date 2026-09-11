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
package io.micronaut.docs.serde

import io.micronaut.context.ApplicationContext
import io.micronaut.serde.ObjectMapper
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Micronaut Serialization against types written in Scala.
 *
 * <p>Serialization reads the introspection the plugin generates, so a case class needs nothing
 * Scala-specific to round-trip -- which is the first thing asserted here. What does need
 * something is Scala's own vocabulary. {@code Option} is a presence the format has no word for,
 * the collections are not {@code java.util} ones, and a Scala 3 {@code enum} is not a
 * {@code java.lang.Enum}, so none of the serializers Micronaut Serialization ships apply to any
 * of them. {@code micronaut-runtime-scala} supplies those, and this is where they are asserted
 * end to end rather than in isolation.</p>
 *
 * <p>Both directions are tested for every type, because they are matched by different rules. A
 * serializer is chosen by what it can accept, so one written for the most general Scala
 * collection covers all of them; a deserializer is chosen by what it can produce, so it has to
 * answer for the exact type the member declares. Writing working has never implied reading
 * working here.</p>
 */
class ScalaSerializationSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext context = ApplicationContext.run()

    @Shared
    ObjectMapper mapper = context.getBean(ObjectMapper)

    void "a case class needs nothing beyond @Serdeable"() {
        when: '''the constructor parameters are the properties, and the introspection the plugin
                 generated is what serialization reads'''
        def json = mapper.writeValueAsString(new Line('sku-1', 2))

        then:
        json == '{"sku":"sku-1","quantity":2}'

        and: 'and reading goes back through that same constructor'
        def read = mapper.readValue(json, Line)
        read.sku() == 'sku-1'
        read.quantity() == 2
    }

    void "a present Option is written as the value itself, not as a wrapper"() {
        when:
        def json = mapper.writeValueAsString(Fixtures.order())

        then: 'notes is the string, with no trace of the Some around it'
        json.contains('"notes":"leave at the door"')

        and: 'and reading gives the Some back'
        def read = mapper.readValue(json, Order)
        read.notes().isDefined()
        read.notes().get() == 'leave at the door'
    }

    void "an empty Option is left out of the document entirely"() {
        when: '''None is absence, and absence in a document is a field that is not there -- not a
                 field set to null, which would say something different'''
        def json = mapper.writeValueAsString(Fixtures.sparseOrder())

        then:
        !json.contains('notes')
    }

    void "a member the document omits is None rather than null"() {
        when: '''the Scala idiom for an optional member is a default of None, and it is also what
                 makes the document and the constructor agree: both may leave it out'''
        def read = mapper.readValue('{"reference":"A-3","status":"Placed","lines":[]}', Order)

        then:
        read.notes() == scala.Option.empty()
        read.notes().isEmpty()

        and: 'the same for a defaulted map, which is empty rather than null'
        read.labels().isEmpty()
    }

    void "an explicit null reads as None"() {
        when: '''a null in the document is exactly what None means. Serialization answers null
                 for a null value without consulting the deserializer unless the deserializer
                 says otherwise, and a null Option is a value the Scala type says cannot exist'''
        def read = mapper.readValue('{"reference":"A-4","status":"Placed","lines":[],"notes":null}', Order)

        then:
        read.notes() == scala.Option.empty()
    }

    void "an Option with no default cannot be filled in from an absent field"() {
        when: '''worth pinning rather than leaving as folklore: Micronaut Serialization decides
                 whether to ask for a default from a fixed list of types that is java.util.Optional
                 and nothing else, so a Scala Option has to bring its own default. Raised upstream
                 as micronaut-serialization#1416; this asserts what happens until that changes,
                 and is the test to invert when it does'''
        def read = mapper.readValue('{"reference":"A-5"}', Undefaulted)

        then: 'which is why the guide says to write `= None`'
        read.notes() == null

        and: 'given that default, the same document produces None'
        mapper.readValue('{"reference":"A-5","status":"Placed","lines":[]}', Order).notes().isEmpty()
    }

    void "an Option holding an introspected type round-trips through that type's own serializer"() {
        when:
        def json = mapper.writeValueAsString(Fixtures.delivery())

        then: 'the option contributes nothing to the document but the presence of the field'
        json == '{"reference":"A-1","signedFor":{"sku":"sku-1","quantity":2}}'

        and:
        def read = mapper.readValue(json, Delivery)
        read.signedFor().get().sku() == 'sku-1'
    }

    void "a Scala collection is an array, and is read back as the type declared"() {
        when:
        def json = mapper.writeValueAsString(Fixtures.order())

        then:
        json.contains('"lines":[{"sku":"sku-1","quantity":2},{"sku":"sku-2","quantity":1}]')

        when:
        def read = mapper.readValue(json, Order)

        then: 'a Scala List, not a java.util.List holding the same elements'
        read.lines() instanceof scala.collection.immutable.List
        read.lines().size() == 2
        read.lines().head().sku() == 'sku-1'
    }

    void "each collection type a member may declare is read back as that type"() {
        given: '''there is one deserializer bean for all of these. It answers for any of them
                  because it is published under a bounded type variable rather than a fixed type,
                  so this asserts the declared type survives rather than collapsing to one shape'''
        def json = mapper.writeValueAsString(Fixtures.shipment())

        when:
        def read = mapper.readValue(json, Shipment)

        then:
        read.parcels() instanceof scala.collection.immutable.Seq
        read.weights() instanceof scala.collection.immutable.Vector
        read.destinations() instanceof scala.collection.immutable.Set
        read.contents() instanceof scala.collection.immutable.Map

        and: 'and holds what was written'
        read.parcels().size() == 2
        read.weights().head() == 3
        read.destinations().contains('Edinburgh')
        read.contents().apply('sku-1') == 2
    }

    void "the general collection types work as well as the immutable ones"() {
        given: 'both are written in Scala sources, and neither is a java.util type'
        def json = mapper.writeValueAsString(Fixtures.manifest())

        when:
        def read = mapper.readValue(json, Manifest)

        then:
        read.entries() instanceof scala.collection.Iterable
        read.ordered() instanceof scala.collection.Seq
        read.distinct() instanceof scala.collection.Set
    }

    void "a Scala Map is a JSON object rather than a list of pairs"() {
        when: '''a Scala Map is an Iterable of Tuple2, so the collection handling would otherwise
                 take it as one and write two-element arrays -- then fail, because a tuple has no
                 introspection'''
        def json = mapper.writeValueAsString(Fixtures.order())

        then:
        json.contains('"labels":{"priority":"high"}')

        and:
        mapper.readValue(json, Order).labels().apply('priority') == 'high'
    }

    void "a map keyed by something other than a string is still a JSON object"() {
        when: '''a JSON key is a string whatever the map's key is. The serializer is declared over
                 any key type for the same reason java.util.Map's is: a serializer is matched by
                 the type arguments it declares, and one written for Map[String, V] did not
                 answer for Map[Int, V]'''
        def json = mapper.writeValueAsString(Fixtures.shipment())

        then:
        json.contains('"byNumber":{"1":"first","2":"second"}')

        and: 'and the keys come back as the declared type'
        def read = mapper.readValue(json, Shipment).byNumber()
        read.apply(1) == 'first'
        read.keySet().head().getClass() == Integer

        and: 'a mutable IndexedSeq is read back as one, which needs a converter of its own'
        def slots = mapper.readValue(json, Shipment).slots()
        slots instanceof scala.collection.mutable.IndexedSeq
        slots.apply(0) == 7
    }

    void "an enum is written by its case name even when it displays itself differently"() {
        when: '''toString is what an enum shows people; the case name is what its valueOf accepts.
                 Writing toString made a serializer reject its own output'''
        def json = mapper.writeValueAsString(Fixtures.ticket())

        then:
        json == '{"priority":"High"}'
        Fixtures.ticket().priority().toString() == 'priority:high'

        and:
        mapper.readValue(json, Ticket) == Fixtures.ticket()
    }

    void "a map holding an introspected type round-trips too"() {
        when:
        def json = mapper.writeValueAsString(Fixtures.shipment())

        then:
        json.contains('"tracking":{"p1":{"sku":"sku-1","quantity":2}}')

        and:
        mapper.readValue(json, Shipment).tracking().apply('p1').quantity() == 2
    }

    void "a Scala 3 enum is its case name"() {
        when: '''a Scala 3 enum is not a java.lang.Enum, so the enum support Micronaut
                 Serialization ships does not apply and the case would be introspected as a bean
                 -- which has no properties, so nothing comes out and nothing goes back in'''
        def json = mapper.writeValueAsString(Fixtures.order())

        then:
        json.contains('"status":"Placed"')

        and: 'and the name is read back through the enum\'s own valueOf'
        mapper.readValue(json, Order).status() == Status.valueOf('Placed')
    }

    void "a name the enum does not have is rejected rather than becoming null"() {
        when:
        mapper.readValue('{"reference":"A-6","status":"Lost","lines":[]}', Order)

        then:
        def e = thrown(Exception)
        e.message.contains('Lost') || e.cause?.message?.contains('Lost')
    }

    void "the whole order survives a round trip unchanged"() {
        given: 'the case class equality is structural, so this compares every member at once'
        def order = Fixtures.order()

        expect:
        mapper.readValue(mapper.writeValueAsString(order), Order) == order

        and:
        mapper.readValue(mapper.writeValueAsString(Fixtures.shipment()), Shipment) == Fixtures.shipment()
        mapper.readValue(mapper.writeValueAsString(Fixtures.delivery()), Delivery) == Fixtures.delivery()
    }
}
