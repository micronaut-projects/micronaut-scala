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
import io.micronaut.docs.idiomatic.AuditLog
import io.micronaut.docs.idiomatic.Banner
import io.micronaut.docs.idiomatic.Broadcaster
import io.micronaut.docs.idiomatic.DataSourceConfig
import io.micronaut.docs.idiomatic.Mode
import io.micronaut.docs.idiomatic.ReportService
import io.micronaut.docs.idiomatic.RunConfig
import io.micronaut.docs.idiomatic.ServerConfig
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

    void "a case class configuration takes its defaults from the constructor"() {
        given: 'only the port is configured'
        def context = ApplicationContext.run(['server.port': 9090])
        def config = context.getBean(ServerConfig)

        expect: "Scala's own default parameter values are the configuration defaults"
        config.host() == 'localhost'
        config.port() == 9090
        config.tags().isEmpty()
        config.banner().isEmpty()

        and: 'and being a case class, it is comparable and printable for free'
        config == new ServerConfig('localhost', 9090, config.tags(), config.banner())
        config.toString().startsWith('ServerConfig(localhost,9090')

        cleanup:
        context.close()
    }

    void "EachProperty produces one case class per configured entry"() {
        given:
        def context = ApplicationContext.run([
            'datasources.one.url': 'jdbc:one',
            'datasources.two.url': 'jdbc:two'
        ])

        expect:
        context.getBeansOfType(DataSourceConfig)
            .collect { "${it.name()}=${it.url()}" }.sort() == ['one=jdbc:one', 'two=jdbc:two']

        cleanup:
        context.close()
    }

    void "an enum bound from configuration must extend java.lang.Enum"() {
        given:
        def context = ApplicationContext.run(['run.mode': 'Fast'])

        expect:
        context.getBean(RunConfig).mode() == Mode.Fast

        and: 'and the constructor default applies when the property is absent'
        def unset = ApplicationContext.run()
        unset.getBean(RunConfig).mode() == Mode.Slow

        cleanup:
        context.close()
        unset?.close()
    }

    void "a trait is the injection point and every implementation of it can be collected"() {
        given:
        def context = ApplicationContext.run()

        expect: 'a Scala collection parameter receives every bean implementing the trait'
        CollectionConverters.asJavaCollection(context.getBean(Broadcaster).broadcast('hi')).toList() ==
            ['email: hi', 'sms: hi']

        and: 'and an Option parameter is None when nothing implements the trait'
        context.getBean(AuditLog).describe() == 'not archived'

        cleanup:
        context.close()
    }

    void "a factory supplies a type the application does not own"() {
        given:
        def context = ApplicationContext.run(['server.host': 'example.com'])

        expect: 'the Clock came from the factory and the config from its own bean'
        context.getBean(ReportService).report() == 'example.com@Z'

        cleanup:
        context.close()
    }

    void "a Scala object can be a bean, at the cost of the seam a class would give"() {
        given:
        def context = ApplicationContext.run()

        expect: 'it injects, but only under the singleton type of that one object'
        context.getBean(Banner).render('ready') == '[app] ready'

        cleanup:
        context.close()
    }

    void "documentation written in Scaladoc reaches the generated configuration metadata"() {
        given: '''the metadata file the IDE and the generated reference documentation read.
                  Nothing configures this: the plugin reads doc comments from the compiler, which
                  keeps them without being asked'''
        def generated = getClass().classLoader.getResources('META-INF/spring-configuration-metadata.json')
            .toList().find { it.text.contains('io.micronaut.docs.documentation.MailerConfig') }

        expect:
        generated != null

        and: 'the group description comes from the class comment'
        generated.text.contains('"description":"Configures the outbound mailer."')

        and: 'and each property description from the @param tag naming it'
        generated.text.contains('"description":"the SMTP host to connect to"')
        generated.text.contains('"description":"the port the SMTP host listens on"')
    }
}
