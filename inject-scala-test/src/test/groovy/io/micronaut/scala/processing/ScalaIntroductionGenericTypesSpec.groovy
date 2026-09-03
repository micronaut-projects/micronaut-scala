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

import io.micronaut.inject.BeanDefinition
import io.micronaut.inject.writer.BeanDefinitionVisitor
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ScalaIntroductionGenericTypesSpec extends AbstractScalaTypeElementSpec {

    void "resolves generic return types for Scala introduction proxy methods"() {
        when:
        def beanDefinition = buildBeanDefinition('test.MyBean' + BeanDefinitionVisitor.PROXY_SUFFIX, '''
package test

import io.micronaut.aop.Introduction
import io.micronaut.aop.Interceptor
import io.micronaut.aop.InvocationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.net.URL
import scala.annotation.StaticAnnotation

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation

@Singleton
class StubIntroduction extends Interceptor[AnyRef, AnyRef]:
  override def intercept(context: InvocationContext[AnyRef, AnyRef]): AnyRef = null

trait MyInterface[T <: URL]:
  def getURL(): T

  def getURLs(): java.util.List[T]

@Stub
@Singleton
@Executable
trait MyBean extends MyInterface[URL]
''')

        then:
        !beanDefinition.isAbstract()
        beanDefinition.injectedFields.isEmpty()
        beanDefinition.executableMethods.size() == 2

        def getUrlMethod = beanDefinition.getRequiredMethod("getURL")
        getUrlMethod.targetMethod.returnType == URL
        getUrlMethod.returnType.type == URL

        def getUrlsMethod = beanDefinition.getRequiredMethod("getURLs")
        getUrlsMethod.returnType.type == List
        getUrlsMethod.returnType.asArgument().hasTypeVariables()
        getUrlsMethod.returnType.asArgument().typeVariables['E'].type == URL
    }

    void "resolves complex generic return types for Scala introduction proxy methods"() {
        when:
        def beanDefinition = buildBeanDefinition('test.MyBean' + BeanDefinitionVisitor.PROXY_SUFFIX, '''
package test

import io.micronaut.aop.Introduction
import io.micronaut.aop.Interceptor
import io.micronaut.aop.InvocationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton

import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import java.net.URL
import scala.annotation.StaticAnnotation

@Introduction
@Type(Array(classOf[StubIntroduction]))
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation

@Singleton
class StubIntroduction extends Interceptor[AnyRef, AnyRef]:
  override def intercept(context: InvocationContext[AnyRef, AnyRef]): AnyRef = null

trait MyInterface[T <: Person]:
  def getPeopleSingle(): reactor.core.publisher.Mono[java.util.List[T]]

  def getPerson(): T

  def getPeople(): java.util.List[T]

  def save(person: T): Unit

  def saveAll(person: java.util.List[T]): Unit

  def getPeopleArray(): Array[T]

  def getPeopleListArray(): java.util.List[Array[T]]

  def getPeopleMap[V <: URL](): java.util.Map[T, V]

@Stub
@Singleton
@Executable
trait MyBean extends MyInterface[SubPerson]

class Person
class SubPerson extends Person
''')

        then:
        !beanDefinition.isAbstract()
        returnType(beanDefinition, "getPerson").type.name == 'test.SubPerson'
        returnType(beanDefinition, "getPeople").type == List
        returnType(beanDefinition, "getPeople").asArgument().hasTypeVariables()
        returnType(beanDefinition, "getPeople").asArgument().typeVariables['E'].type.name == 'test.SubPerson'
        returnType(beanDefinition, "getPeopleMap").typeVariables['K'].type.name == 'test.SubPerson'
        returnType(beanDefinition, "getPeopleMap").typeVariables['V'].type == URL
        returnType(beanDefinition, "getPeopleArray").type.isArray()
        returnType(beanDefinition, "getPeopleArray").type.name.contains('test.SubPerson')
        returnType(beanDefinition, "getPeopleListArray").type == List
        returnType(beanDefinition, "getPeopleListArray").typeVariables['E'].type.isArray()
        returnType(beanDefinition, "getPeopleSingle").typeVariables['T'].type == List
        returnType(beanDefinition, "getPeopleSingle").typeVariables['T'].typeVariables['E'].type.name == 'test.SubPerson'
    }

    private static returnType(BeanDefinition beanDefinition, String name) {
        beanDefinition.findPossibleMethods(name).findFirst().get().returnType
    }
}
