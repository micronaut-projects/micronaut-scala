package io.micronaut.scala.processing

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ZProbeSpec extends AbstractScalaTypeElementSpec {
    void "harness comparison"() {
        given:
        def classLoader = buildClassLoader('oa.PetController', '''
package oa

import io.micronaut.core.annotation.Introspected
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

@Introspected
case class Pet(name: String, age: Int)

@Controller("/pets")
class PetController:
  @Get("/{name}")
  def find(name: String): Pet = Pet(name, 1)
''')
        expect:
        println("PROBE done")
        true
    }
}
