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
import io.micronaut.scala.processing.test.ScalaAnnotatingVisitor
import org.reactivestreams.Publisher

import java.util.function.Supplier

class ScalaIntroducedBeanVisitorSpec extends AbstractScalaTypeElementSpec {

    void "type element visitors can annotate inherited introduction methods with generic return types"() {
        given:
        def context = buildIntroducedContext('introducedbeanspecreturn', '''
trait Repo1:
  def findAll(): Publisher[MyBean]
  def method1(): Publisher[MyBean]

trait Repo2[E]:
  def findAll(): Publisher[E]
  def method2(): Publisher[E]

@RepoDef
trait Repo3 extends Repo2[MyBean], Repo1:
  def method3(): Publisher[MyBean]
''')

        when:
        def beanDef = context.getBeanDefinition(context.classLoader.loadClass('introducedbeanspecreturn.Repo3'))
        def findAllMethod = beanDef.getRequiredMethod('findAll')
        def method1 = beanDef.getRequiredMethod('method1')
        def method2 = beanDef.getRequiredMethod('method2')
        def method3 = beanDef.getRequiredMethod('method3')

        then:
        findAllMethod.hasAnnotation('introducedbeanspecreturn.XMyDataMethod')
        method1.hasAnnotation('introducedbeanspecreturn.XMyDataMethod')
        method2.hasAnnotation('introducedbeanspecreturn.XMyDataMethod')
        method3.hasAnnotation('introducedbeanspecreturn.XMyDataMethod')

        cleanup:
        context?.close()
    }

    void "type element visitors can annotate inherited introduction methods with generic publisher parameters"() {
        given:
        def context = buildIntroducedContext('introducedbeanspecpublisherparam', '''
trait Repo1:
  def findAll(publisher: Publisher[MyBean]): Unit
  def method1(publisher: Publisher[MyBean]): Unit

trait Repo2[E]:
  def findAll(publisher: Publisher[E]): Unit
  def method2(publisher: Publisher[E]): Unit

@RepoDef
trait Repo3 extends Repo2[MyBean], Repo1:
  def method3(publisher: Publisher[MyBean]): Unit
''')

        when:
        def beanDef = context.getBeanDefinition(context.classLoader.loadClass('introducedbeanspecpublisherparam.Repo3'))
        def findAllMethod = beanDef.getRequiredMethod('findAll', Publisher)
        def method1 = beanDef.getRequiredMethod('method1', Publisher)
        def method2 = beanDef.getRequiredMethod('method2', Publisher)
        def method3 = beanDef.getRequiredMethod('method3', Publisher)

        then:
        findAllMethod.hasAnnotation('introducedbeanspecpublisherparam.XMyDataMethod')
        method1.hasAnnotation('introducedbeanspecpublisherparam.XMyDataMethod')
        method2.hasAnnotation('introducedbeanspecpublisherparam.XMyDataMethod')
        method3.hasAnnotation('introducedbeanspecpublisherparam.XMyDataMethod')

        cleanup:
        context?.close()
    }

    void "type element visitors can annotate inherited introduction methods with resolved generic parameters"() {
        given:
        def context = buildIntroducedContext('introducedbeanspecbeanparam', '''
trait Repo1:
  def findAll(bean: MyBean): Unit
  def method1(bean: MyBean): Unit

trait Repo2[E]:
  def findAll(bean: E): Unit
  def method2(bean: E): Unit

@RepoDef
trait Repo3 extends Repo2[MyBean], Repo1:
  def method3(bean: MyBean): Unit
''')

        when:
        def beanType = context.classLoader.loadClass('introducedbeanspecbeanparam.MyBean')
        def beanDef = context.getBeanDefinition(context.classLoader.loadClass('introducedbeanspecbeanparam.Repo3'))
        def findAllMethod = beanDef.getRequiredMethod('findAll', beanType)
        def method1 = beanDef.getRequiredMethod('method1', beanType)
        def method2 = beanDef.getRequiredMethod('method2', beanType)
        def method3 = beanDef.getRequiredMethod('method3', beanType)

        then:
        findAllMethod.hasAnnotation('introducedbeanspecbeanparam.XMyDataMethod')
        method1.hasAnnotation('introducedbeanspecbeanparam.XMyDataMethod')
        method2.hasAnnotation('introducedbeanspecbeanparam.XMyDataMethod')
        method3.hasAnnotation('introducedbeanspecbeanparam.XMyDataMethod')

        cleanup:
        context?.close()
    }

    void "type element visitors can annotate introduction methods discovered through interceptor bean binding"() {
        given:
        def context = buildIntroducedContext('introducedbeanspecinterceptorbean', '''
trait Repo1:
  def findAll(): Publisher[MyBean]
  def method1(): Publisher[MyBean]

trait Repo2[E]:
  def findAll(): Publisher[E]
  def method2(): Publisher[E]

@RepoDef
trait Repo3 extends Repo2[MyBean], Repo1:
  def method3(): Publisher[MyBean]
''', true)

        when:
        def beanDef = context.getBeanDefinition(context.classLoader.loadClass('introducedbeanspecinterceptorbean.Repo3'))
        def findAllMethod = beanDef.getRequiredMethod('findAll')
        def method1 = beanDef.getRequiredMethod('method1')
        def method2 = beanDef.getRequiredMethod('method2')
        def method3 = beanDef.getRequiredMethod('method3')

        then:
        findAllMethod.hasAnnotation('introducedbeanspecinterceptorbean.XMyDataMethod')
        method1.hasAnnotation('introducedbeanspecinterceptorbean.XMyDataMethod')
        method2.hasAnnotation('introducedbeanspecinterceptorbean.XMyDataMethod')
        method3.hasAnnotation('introducedbeanspecinterceptorbean.XMyDataMethod')

        cleanup:
        context?.close()
    }

    private buildIntroducedContext(String packageName, String repoSource, boolean interceptorBean = false) {
        ScalaAnnotatingVisitor.withMethodAnnotation("${packageName}.XMyDataMethod", {
            buildContext("""
package $packageName

import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import org.reactivestreams.Publisher
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.METHOD))
class XMyDataMethod extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[XMyDataMethod]

@Introduction
${interceptorBean ? '' : '@Type(Array(classOf[MyRepoIntroducer]))'}
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class RepoDef extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[RepoDef]

@Singleton
${interceptorBean ? '@InterceptorBean(Array(classOf[RepoDef]))' : ''}
class MyRepoIntroducer extends MethodInterceptor[Object, Object]:
  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    null

class MyBean:
  var name: String = _

$repoSource
""")
        } as Supplier)
    }
}
