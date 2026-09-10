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

// tag::clazz[]
/** A customer order.
  *
  * @param reference the order reference
  * @param status how far along it is
  * @param lines what was ordered
  * @param notes free text the customer may not have supplied
  * @param labels arbitrary tags, by name
  */
@Serdeable
case class Order(
    reference: String,
    status: Status,
    lines: List[Line],
    notes: Option[String] = None,
    labels: Map[String, String] = Map.empty
)
// end::clazz[]
