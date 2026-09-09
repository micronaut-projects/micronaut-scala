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

import io.micronaut.context.exceptions.DependencyInjectionException
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code GenericFactorySpec}.
 *
 * <p>A factory method can be generic, and then the bean it produces is chosen per injection
 * point: the factory reads the type arguments off the point being satisfied and builds
 * accordingly. That needs the method's type variables to survive into the written definition
 * <em>by name</em>, since the factory looks them up as {@code "K"} and {@code "V"}, and it needs
 * their bounds to survive too, because an injection point the bounds exclude must find no bean
 * rather than the wrong one.</p>
 *
 * <p>Generic parity here has been asserted on the element model and on introspections. This is
 * the path where a type variable has to make it all the way through to a runtime lookup, and
 * where losing its name -- or widening it to its erasure, as the hierarchy walk once did --
 * fails as a missing bean rather than as a wrong type.</p>
 */
class ScalaGenericFactoryParitySpec extends AbstractScalaTypeElementSpec {

    private static final String CACHE = '''
trait BaseCache[K, V]
trait Cache[K, V] extends BaseCache[K, V]

class CacheImpl[K, V](val keyType: Class[?], val valueType: Class[?]) extends Cache[K, V]

@Factory
class CacheFactory:
  @Bean
  def buildCache[K <: CharSequence, V](ip: ArgumentInjectionPoint[?, ?]): Cache[K, V] =
    val keyType = ip.asArgument().getTypeVariable("K").get().getType()
    val valueType = ip.asArgument().getTypeVariable("V").get().getType()
    CacheImpl[K, V](keyType, valueType)
'''

    private static String source(String body) {
        '''
package genfact

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.inject.ArgumentInjectionPoint
import jakarta.inject.Inject
import jakarta.inject.Singleton
''' + body + CACHE
    }

    void "resolves a generic factory's type variables from the injection point"() {
        when: 'the same factory method satisfies three points with different type arguments'
        def context = buildContext(source('''
@Singleton
class MyBean(val constructorInject: Cache[String, java.lang.Integer]):
  @Inject var fieldInject: Cache[String, java.lang.Integer] = null
  @Inject var baseInject: BaseCache[String, java.lang.Integer] = null
  // java.lang.StringBuilder spelled out: bare `StringBuilder` in Scala is
  // scala.collection.mutable.StringBuilder, which is a different type entirely
  var methodInject: Cache[java.lang.StringBuilder, java.lang.Float] = null

  @Inject
  def setCache(cache: Cache[java.lang.StringBuilder, java.lang.Float]): Unit = methodInject = cache
'''), [:], true)
        def bean = getBean(context, 'genfact.MyBean')

        then: 'each point got the type arguments written at it, not the erasure'
        bean.constructorInject().keyType() == String
        bean.constructorInject().valueType() == Integer
        bean.fieldInject().keyType() == String
        bean.fieldInject().valueType() == Integer
        bean.methodInject().keyType() == StringBuilder
        bean.methodInject().valueType() == Float

        and: 'including one declared as the supertype the factory does not name'
        bean.baseInject().keyType() == String
        bean.baseInject().valueType() == Integer

        cleanup:
        context?.close()
    }

    void "finds no bean for an injection point the factory's bounds exclude"() {
        when: 'Boolean is not a CharSequence, so K cannot be satisfied'
        def context = buildContext(source('''
@Singleton
class OtherBean(val invalid: Cache[java.lang.Boolean, java.lang.Integer])
'''), [:], true)
        getBean(context, 'genfact.OtherBean')

        then: 'the bound is enforced at resolution, not silently widened'
        def e = thrown(DependencyInjectionException)
        e.message.contains('genfact.Cache<java.lang.Boolean,java.lang.Integer>')

        cleanup:
        context?.close()
    }

    void "produces a bean from a factory method returning a generic array"() {
        when:
        def context = buildContext('''
package genericarray

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

trait Serializer[T]
trait Deserializer[T]
trait Serde[T] extends Serializer[T], Deserializer[T]

class ArraySerde[T] extends Serde[Array[T]]

@Factory
class SerdeFactory:
  @Singleton
  def arraySerde[T](): Serde[Array[T]] = new ArraySerde[T]
''', [:], true)

        then: 'an array type argument on the produced type does not stop the definition'
        context.getBeanDefinition(context.classLoader.loadClass('genericarray.Serde')) != null

        cleanup:
        context?.close()
    }
}
