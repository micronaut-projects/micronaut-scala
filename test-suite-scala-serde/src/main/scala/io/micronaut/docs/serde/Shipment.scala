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

import io.micronaut.serde.annotation.Serdeable

import scala.collection.immutable.Seq
import scala.collection.immutable.Set

/** The collection types a member might be declared as, so each is exercised rather than assumed
  * to follow from `List` working. Nothing here names a concrete implementation: what the member
  * declares is what it is read back as.
  */
@Serdeable
case class Shipment(
    parcels: Seq[String],
    weights: Vector[Int],
    destinations: Set[String],
    contents: Map[String, Int],
    tracking: Map[String, Line]
)

/** An option holding something that itself needs an introspection, which is a different path
  * through the option serde than an option holding a string.
  */
@Serdeable
case class Delivery(reference: String, signedFor: Option[Line])

/** Declared as the general Scala collection types rather than the immutable ones, which is the
  * other way a Scala author writes these.
  */
@Serdeable
case class Manifest(
    entries: scala.collection.Iterable[String],
    ordered: scala.collection.Seq[String],
    distinct: scala.collection.Set[String]
)

/** An option with no default, which is the one case a document that omits the member cannot
  * fill in. See the guide: give it `= None` and both the document and the constructor agree.
  */
@Serdeable
case class Undefaulted(reference: String, notes: Option[String])
