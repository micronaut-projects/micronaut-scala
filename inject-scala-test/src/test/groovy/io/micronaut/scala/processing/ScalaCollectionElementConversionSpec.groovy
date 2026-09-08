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
 * The Scala collection converters ignored the {@link io.micronaut.core.convert.ConversionContext}
 * entirely, so no element was converted to the target's element type. A
 * {@code List[Int]} bound from configuration held {@code String}s while reporting itself as
 * a {@code List[Int]} -- a {@code ClassCastException} the first time an element was used,
 * rather than a conversion error at binding time.
 */
class ScalaCollectionElementConversionSpec extends AbstractScalaTypeElementSpec {

    void 'collection elements are converted to the declared element type'() {
        given:
        def context = buildContext('''
package probe

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
case class AppConfig(
  ports: scala.collection.immutable.List[Int],
  names: scala.collection.immutable.List[String]
)
''', ['app.ports[0]': '8080', 'app.ports[1]': '9090', 'app.names[0]': 'alpha'], true)

        when:
        def config = getBean(context, 'probe.AppConfig')

        then: 'the elements are Integers, not Strings typed as Integers'
        config.ports().head() instanceof Integer
        config.ports().head() == 8080

        and: 'a String element type is of course unaffected'
        config.names().head() == 'alpha'

        and: 'and using an element does not throw'
        config.ports().sum(scala.math.Numeric.IntIsIntegral$.MODULE$) == 17170

        cleanup:
        context?.close()
    }

    void 'map keys and values are converted to the declared types'() {
        given:
        def context = buildContext('''
package probe

import io.micronaut.context.annotation.ConfigurationProperties

@ConfigurationProperties("app")
case class AppConfig(limits: scala.collection.immutable.Map[String, Int])
''', ['app.limits.small': '10', 'app.limits.large': '200'], true)

        when:
        def config = getBean(context, 'probe.AppConfig')

        then:
        config.limits().apply('small') instanceof Integer
        config.limits().apply('small') == 10
        config.limits().apply('large') == 200

        cleanup:
        context?.close()
    }

    void 'a mutable collection converts its elements too'() {
        given:
        def context = buildContext('''
package probe

import io.micronaut.context.annotation.ConfigurationProperties
import scala.collection.mutable

@ConfigurationProperties("app")
case class AppConfig(sizes: mutable.Buffer[Int])
''', ['app.sizes[0]': '3', 'app.sizes[1]': '4'], true)

        when:
        def config = getBean(context, 'probe.AppConfig')

        then:
        config.sizes().head() instanceof Integer
        config.sizes().head() == 3

        cleanup:
        context?.close()
    }
}
