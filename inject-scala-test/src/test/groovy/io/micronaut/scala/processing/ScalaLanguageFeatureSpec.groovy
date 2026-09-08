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

import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ConstructorElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec
import io.micronaut.scala.processing.test.ScalaCompiler

/**
 * Coverage for Scala language constructs that a Scala user meets immediately but that the
 * Java, Groovy and Kotlin parity suites have no equivalent for.
 */
class ScalaLanguageFeatureSpec extends AbstractScalaTypeElementSpec {

    void "test classpath Java superclass is reported as a super type not an interface"() {
        given:
        ClassElement element = buildClassElement('test.Engine', '''
package test

import io.micronaut.scala.processing.fixtures.ExternalMachine

class Engine extends ExternalMachine
''')

        expect:
        element != null
        element.superType.isPresent()
        element.superType.get().name == 'io.micronaut.scala.processing.fixtures.ExternalMachine'
        element.interfaces.every { it.name != 'io.micronaut.scala.processing.fixtures.ExternalMachine' }
    }

    void "test classpath Java interface is reported as an interface"() {
        given:
        ClassElement element = buildClassElement('test.Task', '''
package test

class Task extends Runnable {
  override def run(): Unit = ()
}
''')

        expect:
        element != null
        element.interfaces.any { it.name == 'java.lang.Runnable' }
        !element.superType.isPresent() || element.superType.get().name != 'java.lang.Runnable'
    }

    void "test separate compilation of two Scala sources in one run"() {
        given:
        ClassElement element = buildClassElement([
            ScalaCompiler.SourceFile.scala('test.Other', '''
package test

trait Other {
  def other(): String
}
'''),
            ScalaCompiler.SourceFile.scala('test.Main', '''
package test

import jakarta.inject.Singleton

@Singleton
class Main extends Other {
  override def other(): String = "other"
}
''')
        ], 'test.Main')

        expect:
        element != null
        element.interfaces.any { it.name == 'test.Other' }
    }

    void "test joint Java and Scala compilation in one run"() {
        given:
        ClassElement element = buildClassElement([
            ScalaCompiler.SourceFile.java('test.JointBase', '''
package test;

public class JointBase {
    public String base() {
        return "base";
    }
}
'''),
            ScalaCompiler.SourceFile.scala('test.JointChild', '''
package test

import jakarta.inject.Singleton

@Singleton
class JointChild extends JointBase
''')
        ], 'test.JointChild')

        expect:
        element != null
        element.superType.isPresent()
        element.superType.get().name == 'test.JointBase'
    }

    void "test secondary constructors are exposed"() {
        given:
        ClassElement element = buildClassElement('test.Secondary', '''
package test

class Secondary(val name: String) {
  def this() = this("default")
}
''')

        expect:
        element != null
        element.getEnclosedElements(ElementQuery.of(ConstructorElement)).size() == 2
        element.defaultConstructor.isPresent()
    }

    void "test by-name constructor parameter is modelled as its JVM type"() {
        given:
        ClassElement element = buildClassElement('test.ByName', '''
package test

import jakarta.inject.Singleton

@Singleton
class ByName(value: => String)
''')

        expect:
        element != null
        element.primaryConstructor.isPresent()
        def parameter = element.primaryConstructor.get().parameters[0]
        parameter.type.name == 'scala.Function0'
        parameter.type.firstTypeArgument.get().name == 'java.lang.String'
    }

    void "test trait parameters are exposed on the implementing class"() {
        given:
        ClassElement element = buildClassElement('test.Impl', '''
package test

import io.micronaut.core.annotation.Introspected

trait Base(val identifier: String)

@Introspected
class Impl extends Base("value")
''')

        expect:
        element != null
        element.beanProperties.any { it.name == 'identifier' }
    }

    void "test properties declared on a Scala superclass are inherited"() {
        given:
        ClassElement element = buildClassElement('test.Child', '''
package test

import io.micronaut.core.annotation.Introspected

abstract class Parent(val parentName: String)

@Introspected
class Child(val childName: String) extends Parent("parent")
''')

        expect:
        element != null
        element.beanProperties*.name as Set == ['childName', 'parentName'] as Set
    }

    void "test constructor default arguments do not produce spurious members"() {
        given:
        ClassElement element = buildClassElement('test.Defaulted', '''
package test

import jakarta.inject.Singleton

@Singleton
class Defaulted(val name: String = "fallback")
''')

        expect:
        element != null
        element.primaryConstructor.isPresent()
        element.primaryConstructor.get().parameters.length == 1
        element.beanProperties.size() == 1
        element.beanProperties[0].name == 'name'
        and: 'the synthetic $default$N accessor is not exposed'
        element.getEnclosedElements(ElementQuery.ALL_METHODS).every { !it.name.contains('$default$') }
    }

    void "test varargs method parameter is modelled as its erased Seq type"() {
        given:
        ClassElement element = buildClassElement('test.Varargs', '''
package test

import io.micronaut.context.annotation.Executable
import jakarta.inject.Singleton

@Singleton
class Varargs {
  @Executable
  def sizeOf(values: String*): Int = values.size
}
''')

        expect: 'Scala 3 erases a repeated parameter to scala.collection.immutable.Seq rather than\n        to an array, so that is what the JVM signature and the element model must agree on'
        element != null
        def method = element.getEnclosedElements(ElementQuery.ALL_METHODS).find { it.name == 'sizeOf' }
        method != null
        method.parameters.length == 1
        method.parameters[0].type.name == 'scala.collection.immutable.Seq'
        method.parameters[0].type.firstTypeArgument.get().name == 'java.lang.String'
    }

    void "test contextual using parameters are injection points"() {
        given:
        def definition = buildBeanDefinition('test.Contextual', '''
package test

import jakarta.inject.Singleton

@Singleton
class Dependency

@Singleton
class Contextual(using val dependency: Dependency)
''')

        expect:
        definition != null
        definition.constructor.arguments.length == 1
        definition.constructor.arguments[0].type.name == 'test.Dependency'
    }

    void "test multiple parameter lists are flattened into one constructor"() {
        given:
        def definition = buildBeanDefinition('test.Curried', '''
package test

import jakarta.inject.Singleton

@Singleton
class First

@Singleton
class Second

@Singleton
class Curried(first: First)(second: Second)
''')

        expect:
        definition != null
        definition.constructor.arguments.length == 2
        definition.constructor.arguments[0].type.name == 'test.First'
        definition.constructor.arguments[1].type.name == 'test.Second'
    }

    void "test lazy val is exposed as a single read-only property"() {
        given:
        ClassElement element = buildClassElement('test.Lazily', '''
package test

import io.micronaut.core.annotation.Introspected

@Introspected
class Lazily {
  lazy val name: String = "lazy"
}
''')

        expect:
        element != null
        element.beanProperties.size() == 1
        element.beanProperties[0].name == 'name'
        element.beanProperties[0].readOnly
    }

    void "test BeanProperty accessors are used for introspection"() {
        given:
        def introspection = buildBeanIntrospection('test.Bean', '''
package test

import io.micronaut.core.annotation.Introspected
import scala.beans.BeanProperty

@Introspected
class Bean(@BeanProperty var name: String)
''')

        expect:
        introspection != null
        introspection.propertyNames as Set == ['name'] as Set
    }

    void "test top level definitions do not produce a bean definition"() {
        given:
        def definition = buildBeanDefinition('test.TopLevel', '''
package test

import jakarta.inject.Singleton

def helper(): String = "helper"

@Singleton
class TopLevel
''')

        expect:
        definition != null
        definition.beanType.name == 'test.TopLevel'
    }
    void "test a type alias binds as the type it aliases"() {
        when:
        def context = buildContext('''
package aliasfeature

import io.micronaut.context.annotation.ConfigurationProperties

type Name = String

@ConfigurationProperties("app")
class AppConfig {
  var alias: Name = ""
  var plain: String = ""
}
''', ['app.alias': 'A', 'app.plain': 'P'], true)

        then: 'an alias is transparent, so it must behave exactly as the aliased type'
        def bean = getBean(context, 'aliasfeature.AppConfig')
        bean.alias() == 'A'
        bean.plain() == 'P'

        cleanup:
        context?.close()
    }

    void "test a trait with a self type can be part of a bean"() {
        when:
        def context = buildContext('''
package selftypefeature

import jakarta.inject.Singleton

trait Repo {
  def find(): String = "found"
}

trait Service { self: Repo =>
  def serve(): String = "serve:" + find()
}

@Singleton
class Impl extends Repo with Service
''', [:], true)

        then: 'the self type is a constraint on mixing, not a member of the JVM signature'
        getBean(context, 'selftypefeature.Impl').serve() == 'serve:found'

        cleanup:
        context?.close()
    }

    void "test a higher kinded type argument is reported as its constructor"() {
        when:
        def context = buildContext('''
package hkfeature

import jakarta.inject.Singleton

trait Store[F[_]] {
  def get(): F[String]
}

@Singleton
class OptionStore extends Store[Option] {
  override def get(): Option[String] = Some("x")
}
''', [:], true)

        then:
        getBean(context, 'hkfeature.OptionStore').get().get() == 'x'

        and: 'Store[Option] binds F to the type constructor itself'
        context.getBeanDefinition(context.classLoader.loadClass('hkfeature.OptionStore'))
                .getTypeArguments('hkfeature.Store')*.getType()*.getName() == ['scala.Option']

        cleanup:
        context?.close()
    }

    void "test a value class property binds from configuration as its underlying type"() {
        when: 'a value class erases to its underlying type in a field position'
        def context = buildContext('''
package vcfeature

import io.micronaut.context.annotation.ConfigurationProperties

class Port(val value: Int) extends AnyVal

@ConfigurationProperties("server")
class ServerConfig {
  var port: Port = new Port(0)
  var name: String = ""
}
''', ['server.port': '8080', 'server.name': 'srv'], true)

        then: 'so the property binds as an int, and the bean is usable'
        def bean = getBean(context, 'vcfeature.ServerConfig')
        bean.port() == 8080
        bean.name() == 'srv'

        cleanup:
        context?.close()
    }

}
