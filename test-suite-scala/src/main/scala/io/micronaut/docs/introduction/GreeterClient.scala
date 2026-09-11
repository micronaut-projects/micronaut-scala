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
package io.micronaut.docs.introduction

import io.micronaut.aop.Introduction
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import io.micronaut.context.annotation.Type
import jakarta.inject.Singleton
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

// tag::interceptor[]
@Singleton
class GreeterIntroduction extends MethodInterceptor[AnyRef, Object]:
  override def intercept(context: MethodInvocationContext[AnyRef, Object]): Object =
    s"${context.getMethodName}(${context.getParameterValues.mkString(", ")})"
// end::interceptor[]

// tag::annotation[]
@Introduction
@Type(Array(classOf[GreeterIntroduction]))
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Stub extends StaticAnnotation
// end::annotation[]

// tag::abstract[]
@Stub
trait Greeter:
  def greet(name: String): String
// end::abstract[]
