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
package io.micronaut.scala.processing

import io.micronaut.context.exceptions.NonUniqueBeanException
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code FactoryArraySpec}.
 *
 * <p>A {@code @Bean} method returning an array does not produce one bean holding several values;
 * it produces several beans, each separately injectable and countable. Factory coverage here has
 * only ever produced one bean per method, so the plural case -- and the qualifier that keeps two
 * such methods apart -- was untested.</p>
 *
 * <p>The reason it is worth a Scala test rather than trusting the Java one is the collection
 * types. Micronaut assembles the produced beans into whatever the injection point asks for, and
 * a Scala injection point asks for a Scala collection, which reaches the point through the
 * converters in {@code micronaut-runtime-scala} rather than through core's own container
 * handling. Producing several beans and asking for them as a {@code Seq} exercises both halves
 * at once, and neither half is exercised by the Java spec.</p>
 */
class ScalaFactoryArrayParitySpec extends AbstractScalaTypeElementSpec {

    private static final String SOURCE = '''
package factoryarray

import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import jakarta.inject.Inject
import jakarta.inject.Qualifier
import jakarta.inject.Singleton
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

class Product(val name: String)

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class All extends StaticAnnotation

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
class WishList extends StaticAnnotation

@Factory
class Shop:
  @Bean
  @All
  def allProducts(): Array[Product] =
    Array(Product("one"), Product("two"), Product("three"))

  @Bean
  @WishList
  def wishList(): Array[Product] =
    Array(Product("four"), Product("five"))

@Singleton
class Catalogue(
  @All val all: java.util.List[Product],
  @All val allAsArray: Array[Product],
  @WishList val wishlist: java.util.Set[Product],
  @All val allAsSeq: Seq[Product],
  @WishList val wishlistAsScalaList: List[Product]
)
'''

    void "produces one bean per element of an array a factory returns"() {
        when:
        def context = buildContext(SOURCE, [:], true)
        def productType = context.classLoader.loadClass('factoryarray.Product')
        def catalogue = getBean(context, 'factoryarray.Catalogue')

        then: 'two producing methods, and the definitions are per method not per element'
        context.getBeanDefinitions(productType).size() == 2

        and: 'but each element is its own bean at the injection point'
        catalogue.all().size() == 3
        catalogue.allAsArray().length == 3
        catalogue.wishlist().size() == 2
        catalogue.wishlist().collect { it.name() }.toSet() == ['four', 'five'] as Set

        cleanup:
        context?.close()
    }

    void "assembles the produced beans into the Scala collection the point asks for"() {
        when:
        def context = buildContext(SOURCE, [:], true)
        def catalogue = getBean(context, 'factoryarray.Catalogue')

        then: 'a Seq and a Scala List are filled the same way a java.util.List is'
        catalogue.allAsSeq().size() == 3
        catalogue.wishlistAsScalaList().size() == 2
        catalogue.wishlistAsScalaList().apply(0).name() in ['four', 'five']

        cleanup:
        context?.close()
    }

    void "refuses to resolve the element type without a qualifier"() {
        when:
        def context = buildContext(SOURCE, [:], true)
        def productType = context.classLoader.loadClass('factoryarray.Product')
        context.getBean(productType)

        then: 'five beans of the type exist, so a bare lookup is ambiguous'
        thrown(NonUniqueBeanException)

        when: 'and a qualifier that still matches more than one is no better'
        context.getBean(productType, Qualifiers.byStereotype('factoryarray.WishList'))

        then:
        thrown(NonUniqueBeanException)

        cleanup:
        context?.close()
    }
}
