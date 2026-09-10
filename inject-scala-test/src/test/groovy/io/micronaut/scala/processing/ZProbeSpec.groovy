package io.micronaut.scala.processing

import io.micronaut.inject.ast.ElementQuery
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

class ZProbeSpec extends AbstractScalaTypeElementSpec {
    void "does an annotation on an inherited classpath method persist"() {
        given:
        def element = buildClassElement('meta.Repo', '''
package meta

import io.micronaut.data.repository.CrudRepository

case class Book(id: java.lang.Long, title: String)

trait Repo extends CrudRepository[Book, java.lang.Long]
''')
        def first = element.getEnclosedElements(ElementQuery.ALL_METHODS.named('findAll'))[0]
        first.annotate('jakarta.inject.Named') { b -> b.value('marked') }

        when: 'the same method is asked for again, as the writer does after the visitors ran'
        def second = element.getEnclosedElements(ElementQuery.ALL_METHODS.named('findAll'))[0]

        then:
        println("PROBE sameInstance=" + first.is(second))
        println("PROBE firstHas=" + first.annotationMetadata.hasAnnotation('jakarta.inject.Named'))
        println("PROBE secondHas=" + second.annotationMetadata.hasAnnotation('jakarta.inject.Named'))
        true
    }
}
