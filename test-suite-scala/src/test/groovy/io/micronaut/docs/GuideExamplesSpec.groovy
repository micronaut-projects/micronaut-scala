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
package io.micronaut.docs

import io.micronaut.context.ApplicationContext
import io.micronaut.core.beans.BeanIntrospection
import io.micronaut.docs.aop.AuditInterceptor
import io.micronaut.docs.aop.OrderService
import io.micronaut.docs.config.EngineConfiguration
import io.micronaut.docs.di.Journey
import io.micronaut.docs.di.Vehicle
import io.micronaut.docs.helloworld.GreetingService
import io.micronaut.docs.introduction.Greeter
import io.micronaut.docs.introspection.Book
import io.micronaut.docs.serialization.Order
import io.micronaut.serde.ObjectMapper
import scala.jdk.javaapi.CollectionConverters
import spock.lang.Specification

/**
 * Runs the examples the guide includes with {@code snippet::}.
 *
 * <p>A snippet that is compiled but never executed only proves the source parses. These run
 * each example the way the guide says it behaves, so a change that keeps the examples
 * compiling while breaking what the surrounding prose claims still fails here.</p>
 */
class GuideExamplesSpec extends Specification {

    void "the quick start beans resolve by constructor injection"() {
        given:
        def context = ApplicationContext.run()

        expect:
        context.getBean(GreetingService).greet('Scala') == 'Hello, Scala'

        cleanup:
        context.close()
    }

    void "configuration binds Option and Scala collections"() {
        given: 'nickname is deliberately absent'
        def context = ApplicationContext.run([
            'engine.manufacturer': 'Ford',
            'engine.cylinders'   : [1, 2, 3, 4]
        ])
        def configuration = context.getBean(EngineConfiguration)

        expect:
        configuration.manufacturer() == 'Ford'
        configuration.nickname().isEmpty()
        configuration.cylinders().size() == 4

        when: 'the property is present'
        def named = ApplicationContext.run([
            'engine.manufacturer': 'Ford',
            'engine.nickname'    : 'Coyote',
            'engine.cylinders'   : [8]
        ])

        then:
        named.getBean(EngineConfiguration).nickname().get() == 'Coyote'

        cleanup:
        context.close()
        named?.close()
    }

    void "a case class is introspectable"() {
        given:
        BeanIntrospection<Book> introspection = BeanIntrospection.getIntrospection(Book)

        expect:
        introspection.propertyNames as Set == ['title', 'pages'] as Set
        introspection.instantiate('Dune', 412).title() == 'Dune'
    }

    void "a qualifier picks between two implementations of the same trait"() {
        given:
        def context = ApplicationContext.run()

        expect:
        context.getBean(Vehicle).start() == 'V8 starting'

        and: 'a singleton is the same instance every time, a prototype is not'
        context.getBean(Vehicle).is(context.getBean(Vehicle))
        context.getBean(Journey).id() != context.getBean(Journey).id()

        cleanup:
        context.close()
    }

    void "around advice runs only for the methods it annotates"() {
        given:
        def context = ApplicationContext.run()
        def orders = context.getBean(OrderService)

        when:
        def placed = orders.place('a book')
        def quoted = orders.quote('a book')

        then: 'the advised method still returns what it returns'
        placed == 'ordered a book'
        quoted == 'quote for a book'

        and: 'and the interceptor saw the annotated method and not the other'
        CollectionConverters.asJavaCollection(context.getBean(AuditInterceptor).audited()).toList() == ['place']

        cleanup:
        context.close()
    }

    void "introduction advice implements an abstract member"() {
        given: 'Greeter is a trait with no implementation anywhere'
        def context = ApplicationContext.run()

        expect:
        context.getBean(Greeter).greet('Scala') == 'greet(Scala)'

        cleanup:
        context.close()
    }

    void "a Serdeable case class round-trips through JSON"() {
        given:
        def context = ApplicationContext.run()
        def mapper = context.getBean(ObjectMapper)

        when:
        def json = mapper.writeValueAsString(new Order('A-1', 3))

        then:
        json == '{"id":"A-1","quantity":3}'

        and: 'and reading it back reaches the constructor the introspection describes'
        mapper.readValue(json, Order) == new Order('A-1', 3)

        cleanup:
        context.close()
    }
}
