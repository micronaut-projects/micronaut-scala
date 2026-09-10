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
package io.micronaut.docs.idiomatic

import jakarta.inject.Singleton

// tag::trait[]
trait Notifier:
  def send(message: String): String
// end::trait[]

// tag::implementations[]
@Singleton
class EmailNotifier extends Notifier:
  def send(message: String): String = s"email: $message"

@Singleton
class SmsNotifier extends Notifier:
  def send(message: String): String = s"sms: $message"
// end::implementations[]

// tag::collection[]
@Singleton
class Broadcaster(notifiers: List[Notifier]):
  def broadcast(message: String): List[String] =
    notifiers.map(_.send(message)).sorted
// end::collection[]

// tag::optional[]
trait Archive:
  def store(message: String): Unit

@Singleton
class AuditLog(archive: Option[Archive]):
  def describe(): String = archive.fold("not archived")(_ => "archived")
// end::optional[]

// tag::objectBean[]
@Singleton
object HouseStyle:
  def prefix: String = "[app]"

@Singleton
class Banner(style: HouseStyle.type):
  def render(text: String): String = s"${style.prefix} $text"
// end::objectBean[]
