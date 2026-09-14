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
package io.micronaut.docs.aop

import io.micronaut.aop.Around
import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation
import scala.collection.mutable.ListBuffer

// tag::annotation[]
@Around
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
class Audited extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Audited]
// end::annotation[]

// tag::interceptor[]
@Singleton
@InterceptorBean(Array(classOf[Audited]))
class AuditInterceptor extends MethodInterceptor[Object, Object]:
  private val calls = ListBuffer.empty[String]

  def audited: Seq[String] = calls.toSeq

  override def intercept(context: MethodInvocationContext[Object, Object]): Object =
    calls += context.getMethodName
    context.proceed()
// end::interceptor[]

// tag::bean[]
@Singleton
class OrderService:
  @Audited
  def place(item: String): String = s"ordered $item"

  def quote(item: String): String = s"quote for $item"
// end::bean[]
