package io.micronaut.docs.data

import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository

/** Books, stored in the default data source. */
@JdbcRepository(dialect = Dialect.H2)
trait BookRepository extends CrudRepository[Book, java.lang.Long]:

  /** Finds every book with the given title. */
  def findByTitle(title: String): java.util.List[Book]

  /** Counts the books longer than the given length. */
  def countByPagesGreaterThan(pages: Int): Long
