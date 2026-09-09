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

import io.micronaut.context.exceptions.NoSuchBeanException
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code RequiresSpec},
 * {@code RequiresBeanCompileSpec}, {@code EachBeanReplacesSpec} and
 * {@code EachBeanInterceptorSpec}.
 *
 * <p>A requirement is evaluated at runtime from what the definition recorded, so every form has
 * to survive the write: a required bean, a bean required to be <em>absent</em>, and a replacement
 * that has to be matched per configuration entry rather than per type.</p>
 *
 * <p>The missing-bean form is the one worth asserting, because it is satisfied by nothing being
 * there -- a requirement that failed to reach the definition looks identical to one that was met.
 * </p>
 */
class ScalaRequiresConditionParitySpec extends AbstractScalaTypeElementSpec {

    void "loads a bean only when a required bean is present"() {
        when: 'the required bean exists'
        def present = buildContext('''
package requirescond

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Singleton
class Dependency

@Singleton
@Requires(beans = Array(classOf[Dependency]))
class Dependent
''', [:], true)

        then:
        getBean(present, 'requirescond.Dependent') != null

        when: 'and the same requirement with nothing to satisfy it'
        def absent = buildContext('''
package requirescond

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

class Dependency

@Singleton
@Requires(beans = Array(classOf[Dependency]))
class Dependent
''', [:], true)
        getBean(absent, 'requirescond.Dependent')

        then:
        thrown(NoSuchBeanException)

        cleanup:
        present?.close()
        absent?.close()
    }

    void "loads a bean only while another is missing"() {
        when: 'nothing provides the named type, so the fallback applies'
        def fallback = buildContext('''
package requirescond

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

trait Store:
  def name(): String

class Primary extends Store:
  override def name(): String = "primary"

@Singleton
@Requires(missingBeans = Array(classOf[Primary]))
class Fallback extends Store:
  override def name(): String = "fallback"
''', [:], true)

        then:
        getBean(fallback, 'requirescond.Fallback').name() == 'fallback'

        when: 'and the same requirement once that type is a bean'
        def suppressed = buildContext('''
package requirescond

import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

trait Store:
  def name(): String

@Singleton
class Primary extends Store:
  override def name(): String = "primary"

@Singleton
@Requires(missingBeans = Array(classOf[Primary]))
class Fallback extends Store:
  override def name(): String = "fallback"
''', [:], true)
        getBean(suppressed, 'requirescond.Fallback')

        then: 'the fallback is withdrawn, which nothing being there would also look like'
        thrown(NoSuchBeanException)

        cleanup:
        fallback?.close()
        suppressed?.close()
    }

    void "replaces one bean of an EachBean set without replacing the others"() {
        when:
        def context = buildContext('''
package requirescond

import io.micronaut.context.annotation.EachBean
import io.micronaut.context.annotation.EachProperty
import io.micronaut.context.annotation.Parameter
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Named
import jakarta.inject.Singleton

@EachProperty("servers")
class ServerConfig(@Parameter val name: String)

@EachBean(classOf[ServerConfig])
class ServerClient(val config: ServerConfig):
  def describe(): String = "default-" + config.name

@Singleton
@Named("one")
@Replaces(bean = classOf[ServerClient], named = "one")
class CustomClient extends ServerClient(null):
  override def describe(): String = "custom-one"
''', ['servers.one.host': 'a', 'servers.two.host': 'b'], true)
        def clientType = context.classLoader.loadClass('requirescond.ServerClient')

        then: 'the named entry is replaced'
        context.getBean(clientType, Qualifiers.byName('one')).describe() == 'custom-one'

        and: 'and the other entry is untouched'
        context.getBean(clientType, Qualifiers.byName('two')).describe() == 'default-two'

        cleanup:
        context?.close()
    }
}
