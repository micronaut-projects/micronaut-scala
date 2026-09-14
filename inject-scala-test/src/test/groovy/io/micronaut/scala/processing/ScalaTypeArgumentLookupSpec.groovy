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
import io.micronaut.scala.processing.test.ScalaVisitorContextCaptureVisitor

/**
 * {@code getTypeArguments(String)} is how Micronaut answers "what is {@code T} for the
 * {@code EventListener<T>} this bean implements", and it is built on
 * {@code getSuperType()} / {@code getInterfaces()}. A classpath element read those from
 * {@code Class.getSuperclass()} and {@code Class.getInterfaces()}, which are erased, so a
 * bound argument was reported as the type variable's erasure instead: for
 * {@code String implements Comparable<String>} the answer was {@code java.lang.Object}.
 * A type argument the element itself binds also has to be substituted into its
 * supertypes, or the answer stops at the first link of the chain.
 */
class ScalaTypeArgumentLookupSpec extends AbstractScalaTypeElementSpec {

    private static Map names(Map typeArguments) {
        typeArguments.collectEntries { name, argument -> [name, argument?.getName()] }
    }

    private Map lookUp(Closure<Map> lookup) {
        def found = [:]
        ScalaVisitorContextCaptureVisitor.withConsumer({ context ->
            found.putAll(lookup(context))
        }, { buildClassLoader('probe.X', 'package probe\n\nclass X\n') })
        found
    }

    void 'a classpath type resolves the argument its interface is bound to'() {
        when: 'java.lang.String implements Comparable<String>'
        def found = lookUp { context ->
            def element = context.getClassElement('java.lang.String').orElse(null)
            [comparable: names(element.getTypeArguments('java.lang.Comparable'))]
        }

        then: 'the erased Class.getInterfaces() answered java.lang.Object here'
        found.comparable == [T: 'java.lang.String']
    }

    void 'a classpath type substitutes its own arguments into its supertypes'() {
        when: 'ArrayList<String>, whose own E has to reach List, Collection and Iterable'
        def found = lookUp { context ->
            def string = context.getClassElement('java.lang.String').orElse(null)
            def element = context.getClassElement('java.util.ArrayList').orElse(null)
                .withTypeArguments([E: string])
            [
                list: names(element.getTypeArguments('java.util.List')),
                iterable: names(element.getTypeArguments('java.lang.Iterable'))
            ]
        }

        then: 'the binding has to be carried down the whole chain, not just one level'
        found.list == [E: 'java.lang.String']
        found.iterable == [T: 'java.lang.String']
    }

    void 'a source type resolves the argument of a directly implemented trait'() {
        given:
        def element = buildClassElement('probe.Handler', '''
package probe

trait Consumer[T] {
  def accept(item: T): Unit
}

class Handler extends Consumer[String] {
  override def accept(item: String): Unit = ()
}
''')

        expect:
        names(element.getTypeArguments('probe.Consumer')) == [T: 'java.lang.String']
    }

    void 'a source type resolves an argument through an intermediate superclass'() {
        given:
        def element = buildClassElement('probe.Handler', '''
package probe

trait Consumer[T] {
  def accept(item: T): Unit
}

abstract class Base[E] extends Consumer[E]

class Handler extends Base[String] {
  override def accept(item: String): Unit = ()
}
''')

        when:
        def all = element.getAllTypeArguments()

        then: 'Handler binds Base[E], and Base passes E on to Consumer[T]'
        names(all['probe.Base']) == [E: 'java.lang.String']
        names(all['probe.Consumer']) == [T: 'java.lang.String']
    }
}
