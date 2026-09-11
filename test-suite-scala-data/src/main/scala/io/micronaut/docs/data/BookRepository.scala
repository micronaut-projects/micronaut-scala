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
package io.micronaut.docs.data

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

// tag::clazz[]
/** Books, stored in the default data source. */
@JdbcRepository(dialect = Dialect.H2)
trait BookRepository extends CrudRepository[Book, Long]:

  /** Finds every book with the given title. */
  def findByTitle(title: String): List[Book]

  /** Counts the books longer than the given length. */
  def countByPagesGreaterThan(pages: Int): Long

  /** Finds one book with the given title, if there is one. */
  def findFirstByTitle(title: String): java.util.Optional[Book]
// end::clazz[]
