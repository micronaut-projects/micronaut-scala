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
package io.micronaut.docs.di

import io.micronaut.context.annotation.Prototype
import jakarta.inject.Named
import jakarta.inject.Singleton

// tag::interface[]
trait Engine:
  def start(): String
// end::interface[]

// tag::implementations[]
@Singleton
@Named("v6")
class V6Engine extends Engine:
  def start(): String = "V6 starting"

@Singleton
@Named("v8")
class V8Engine extends Engine:
  def start(): String = "V8 starting"
// end::implementations[]

// tag::injection[]
@Singleton
class Vehicle(@Named("v8") engine: Engine):
  def start(): String = engine.start()
// end::injection[]

// tag::prototype[]
@Prototype
class Journey:
  val id: String = java.util.UUID.randomUUID().toString
// end::prototype[]
