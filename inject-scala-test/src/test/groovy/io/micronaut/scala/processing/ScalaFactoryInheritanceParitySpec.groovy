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

import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P1 parity, ported from {@code inject-java}'s {@code FactoryAbstractInheritanceSpec}.
 *
 * <p>A producer method can be inherited, and then one method declaration backs several
 * factories. Each has to produce its own bean through the factory it was reached from, so this
 * turns on the distinction between where a method is declared and which type it is owned by --
 * the same distinction that, read the wrong way round, made advice on an inherited trait method
 * resolve against the trait instead of the bean.</p>
 *
 * <p>Getting it wrong is quiet: the beans still appear, just fewer of them, or all built through
 * one factory. Two concrete factories over one declaration is the smallest arrangement that
 * tells the difference, and Scala can build it two ways -- an abstract class, and a trait, which
 * is how a Scala author would actually share a producer.</p>
 */
class ScalaFactoryInheritanceParitySpec extends AbstractScalaTypeElementSpec {

    private static String source(String base) {
        '''
package factinherit

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

class Product

''' + base + '''

@Factory
class FirstFactory extends ProductSource

@Factory
class SecondFactory extends ProductSource
'''
    }

    void "produces one bean per factory from a method inherited from an abstract class"() {
        when:
        def context = buildContext(source('''
abstract class ProductSource:
  @Singleton
  def product(): Product = new Product
'''), [:], true)
        def productType = context.classLoader.loadClass('factinherit.Product')

        then: 'two factories, two beans -- not one shared between them'
        context.getBeansOfType(productType).size() == 2

        and: 'and each definition is owned by the concrete factory it was reached through'
        context.getBeanDefinitions(productType)*.beanDefinitionName.sort() == [
            'factinherit.$FirstFactory$Product0$Definition',
            'factinherit.$SecondFactory$Product0$Definition'
        ]

        cleanup:
        context?.close()
    }

    void "produces one bean per factory from a method inherited from a trait"() {
        when: 'the same producer shared the way a Scala author would share it'
        def context = buildContext(source('''
trait ProductSource:
  @Singleton
  def product(): Product = new Product
'''), [:], true)
        def productType = context.classLoader.loadClass('factinherit.Product')

        then: 'a trait is an interface, and the concrete method on it is a default method'
        context.getBeansOfType(productType).size() == 2
        context.getBeanDefinitions(productType)*.beanDefinitionName.sort() == [
            'factinherit.$FirstFactory$Product0$Definition',
            'factinherit.$SecondFactory$Product0$Definition'
        ]

        cleanup:
        context?.close()
    }

    void "does not produce a bean for the abstract factory itself"() {
        when: 'only the concrete factories carry @Factory'
        def context = buildContext(source('''
abstract class ProductSource:
  @Singleton
  def product(): Product = new Product
'''), [:], true)

        then: 'the base declares the producer but is not a factory, so nothing is written for it'
        context.beanDefinitionReferences.every {
            !it.beanDefinitionName.contains('ProductSource')
        }

        cleanup:
        context?.close()
    }
}
