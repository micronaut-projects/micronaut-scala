package io.micronaut.docs.data

import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity

/** A book on the shelf.
  *
  * @param id the generated primary key
  * @param title the book's title
  * @param pages how many pages it has
  */
@MappedEntity
case class Book(
    @Id @GeneratedValue id: java.lang.Long,
    title: String,
    pages: Int
)
