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

import io.micronaut.core.beans.BeanIntrospector
import io.micronaut.scala.processing.test.AbstractScalaTypeElementSpec

/**
 * P3 parity, ported from {@code inject-java}'s {@code ImportTypeElementSpec} and
 * {@code MixinSpec}.
 *
 * <p>{@code @ClassImport} introspects a type the annotated class does not own, and
 * {@code @Mixin} annotates one from a separate declaration. Together they are how a type nobody
 * controls -- a third-party model, a generated class -- is made introspectable, which is the
 * whole reason serialization uses them.</p>
 *
 * <p>An import on its own introspects nothing, in Java as here: core's own
 * {@code ImportTypeElementSpec} passes {@code annotate = Introspected.class} in every case, and
 * {@code IntrospectedTypeElementVisitor.visitClass} returns immediately for an element without
 * the stereotype. What the import contributes is reaching the type at all and marking it with
 * {@code @ImportedClass}, which is what gives the written introspection an originating element
 * and a package to be written into.</p>
 */
class ScalaClassImportParitySpec extends AbstractScalaTypeElementSpec {

    private static Object introspectionFor(ClassLoader classLoader, String className) {
        BeanIntrospector.forClassLoader(classLoader)
            .findIntrospection(classLoader.loadClass(className)).orElse(null)
    }

    void "introspects a type named by ClassImport rather than annotated itself"() {
        when: '''Imported carries no @Introspected of its own -- the import both reaches it and
                 applies the annotation, which is the shape every case in core's
                 ImportTypeElementSpec uses'''
        def classLoader = buildClassLoader('classimport.Importer', '''
package classimport

import io.micronaut.context.annotation.ClassImport
import io.micronaut.core.annotation.Introspected

class Imported:
  var name: String = null
  var size: Int = 0

@ClassImport(classes = Array(classOf[Imported]), annotate = Array(classOf[Introspected]))
class Importer
''')
        // Resolved by type rather than by generated name: the introspection is written for the
        // importing element, so its class name is not derived from the imported type.
        def introspection = introspectionFor(classLoader, 'classimport.Imported')

        then: 'the import is what makes it introspectable'
        introspection != null
        introspection.propertyNames.toList().sort() == ['name', 'size']
    }

    void "annotates an imported type through a mixin"() {
        when: '''the mixin declares a member of the same name and annotates it, and carries the
                 @Introspected that makes the target introspectable -- the shape core's own
                 MixinSpec uses. What a mixin moves is annotations: the property keeps its own
                 name and gains the annotation, it is not renamed by one'''
        def classLoader = buildClassLoader('classimport.Importer', '''
package classimport

import io.micronaut.context.annotation.ClassImport
import io.micronaut.context.annotation.Mixin
import io.micronaut.core.annotation.Introspected
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import scala.annotation.StaticAnnotation

@Retention(RetentionPolicy.RUNTIME)
class Marker(val value: String) extends StaticAnnotation

class Imported:
  var name: String = null

@Mixin(classOf[Imported])
@Introspected
class ImportedMixin:
  @Marker("hello")
  var name: String = null

@ClassImport(classes = Array(classOf[Imported]))
class Importer
''')
        def introspection = introspectionFor(classLoader, 'classimport.Imported')

        then: 'the @Introspected on the mixin is what makes the imported type introspectable'
        introspection != null

        and: 'and the annotation written on the mixin reaches the property of the same name'
        introspection.getProperty('name').get().stringValue('classimport.Marker').get() == 'hello'
    }
}
