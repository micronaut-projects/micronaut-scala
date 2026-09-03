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

import io.micronaut.context.ApplicationContext
import io.micronaut.context.exceptions.DependencyInjectionException
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ScalaAutowiredSpec extends AbstractScalaTypeElementSpec {

    void "supports required Scala autowired fields"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.compiletime.uninitialized

@Singleton
class Test:
  @(Autowired @field)
  var foo: Foo = uninitialized

@Singleton
class Foo
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.foo() != null

        cleanup:
        context?.close()
    }

    void "supports qualified Scala autowired fields"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Named
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.compiletime.uninitialized

@Singleton
class Test:
  @(Autowired @field)
  @(Named @field)("two")
  var foo: Foo = uninitialized

@Singleton
@Named("one")
case class OneFoo() extends Foo:
  override def name: String = "one"

@Singleton
@Named("two")
case class TwoFoo() extends Foo:
  override def name: String = "two"

trait Foo:
  def name: String
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.foo().name() == 'two'

        cleanup:
        context?.close()
    }

    void "fails required Scala autowired field injection when dependency is missing"() {
        when:
        ApplicationContext context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton
import scala.annotation.meta.field
import scala.compiletime.uninitialized

@Singleton
class Test:
  @(Autowired @field)
  var foo: Foo = uninitialized

class Foo
''')
        getBean(context, 'test.Test')

        then:
        thrown(DependencyInjectionException)

        cleanup:
        context?.close()
    }

    void "supports optional Scala autowired fields"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton
import scala.annotation.meta.field

@Singleton
class Test:
  @(Autowired @field)(required = false)
  var foo: Foo = Foo("test")

case class Foo(name: String)
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.foo().name() == 'test'

        cleanup:
        context?.close()
    }

    void "supports optional Scala autowired fields with value annotations"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.Value
import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton
import scala.annotation.meta.field

@Singleton
class Test:
  @(Autowired @field)(required = false)
  @(Value @field)("${foo.bar}")
  var value: String = "unchanged"
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.value() == 'unchanged'

        cleanup:
        context?.close()
    }

    void "supports required Scala autowired methods"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.context.annotation.Value
import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton

@Singleton
class Test:
  var value: Foo = Foo("unchanged")

  @Autowired
  def setValue(value: Foo): Unit =
    this.value = value

@Singleton
case class Foo(@Value("injected") name: String)
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.value().name() == 'injected'

        cleanup:
        context?.close()
    }

    void "supports optional Scala autowired methods"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton

@Singleton
class Test:
  var value: Foo = Foo("unchanged")

  @Autowired(required = false)
  def setValue(value: Foo): Unit =
    this.value = value

case class Foo(name: String)
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.value().name() == 'unchanged'

        cleanup:
        context?.close()
    }

    void "skips optional Scala autowired methods when any argument is missing"() {
        given:
        def context = buildContext('''
package test

import io.micronaut.inject.autowired.Autowired
import jakarta.inject.Singleton

@Singleton
class Test:
  var value: Foo = Foo("unchanged")

  @Autowired(required = false)
  def setValues(bar: Bar, value: Foo): Unit =
    this.value = value

case class Foo(name: String)

@Singleton
class Bar
''')
        def bean = getBean(context, 'test.Test')

        expect:
        bean.value().name() == 'unchanged'

        cleanup:
        context?.close()
    }
}
