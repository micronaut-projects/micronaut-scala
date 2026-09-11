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
package io.micronaut.docs.serde

/** Builds the sample values, so the tests can be written in Groovy without constructing Scala
  * collections and options from Java call sites.
  */
object Fixtures:

  def order: Order = Order(
    reference = "A-1",
    status = Status.Placed,
    lines = List(Line("sku-1", 2), Line("sku-2", 1)),
    notes = Some("leave at the door"),
    labels = Map("priority" -> "high")
  )

  /** The same order with everything optional left out, which is what an author gets by
    * constructing it without those arguments at all.
    */
  def sparseOrder: Order = Order("A-2", Status.Shipped, List(Line("sku-3", 1)))

  def shipment: Shipment = Shipment(
    parcels = Seq("p1", "p2"),
    weights = Vector(3, 4),
    destinations = Set("Edinburgh"),
    contents = Map("sku-1" -> 2),
    tracking = Map("p1" -> Line("sku-1", 2)),
    slots = scala.collection.mutable.ArrayBuffer(7, 8),
    byNumber = Map(1 -> "first", 2 -> "second")
  )

  def ticket: Ticket = Ticket(Priority.High)

  def delivery: Delivery = Delivery("A-1", Some(Line("sku-1", 2)))

  def manifest: Manifest = Manifest(Iterable("a"), Seq("b"), Set("c"))
