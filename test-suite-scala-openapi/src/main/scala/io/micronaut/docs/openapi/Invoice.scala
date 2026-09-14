package io.micronaut.docs.openapi

import io.micronaut.jsonschema.JsonSchema

/** A customer invoice.
  *
  * @param reference the reference printed on the invoice
  * @param amount the amount due, in whole currency units
  */
@JsonSchema
case class Invoice(reference: String, amount: Int)
