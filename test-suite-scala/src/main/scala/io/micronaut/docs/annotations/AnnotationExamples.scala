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
package io.micronaut.docs.annotations

import io.micronaut.aop.InterceptorBinding
import io.micronaut.aop.InterceptorBindingDefinitions
import io.micronaut.aop.InterceptorKind
import io.micronaut.core.annotation.Introspected
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import scala.annotation.StaticAnnotation

// tag::arrayMember[]
@Introspected(accessKind = Array(Introspected.AccessKind.METHOD))
class Vehicle(val make: String)
// end::arrayMember[]

// tag::namedMember[]
@Introspected
class Part:
  @Introspected.Property(value = "part_number")
  def partNumber: String = "none"
  def partNumber_=(value: String): Unit = ()
// end::namedMember[]

// tag::container[]
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE, ElementType.METHOD))
@InterceptorBindingDefinitions(Array(
  new InterceptorBinding(kind = InterceptorKind.POST_CONSTRUCT),
  new InterceptorBinding(kind = InterceptorKind.PRE_DESTROY)
))
class Managed extends StaticAnnotation, java.lang.annotation.Annotation:
  override def annotationType(): Class[? <: java.lang.annotation.Annotation] =
    classOf[Managed]
// end::container[]

// tag::declaring[]
@Retention(RetentionPolicy.RUNTIME)
@Target(Array(ElementType.TYPE))
class Audited(val category: String = "general") extends StaticAnnotation

@Audited(category = "billing")
class Invoice(val total: Long)
// end::declaring[]
