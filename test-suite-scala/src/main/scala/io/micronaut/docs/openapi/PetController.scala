package io.micronaut.docs.openapi

import io.micronaut.core.annotation.Introspected
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

/** A pet. */
@Introspected
case class Pet(name: String, age: Int)

/** Operations over pets. */
@Controller("/pets")
class PetController:
  /** Finds a pet by name. */
  @Get("/{name}")
  def find(name: String): Pet = Pet(name, 1)
