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
import spock.lang.PendingFeature

/**
 * P3 parity, ported from {@code inject-java}'s {@code ImportTypeElementSpec} and
 * {@code MixinSpec}.
 *
 * <p>{@code @ClassImport} introspects a type the annotated class does not own, and
 * {@code @Mixin} annotates one from a separate declaration. Together they are how a type nobody
 * controls -- a third-party model, a generated class -- is made introspectable, which is the
 * whole reason serialization uses them.</p>
 *
 * <p>Both are marked pending. The import produces no introspection here, and the reason string
 * carries how far the investigation got, so the next attempt does not repeat it: the imported
 * name <em>does</em> resolve, since {@code VisitorContext.getClassElement} checks source classes
 * before the classpath, so the gap is past resolution.</p>
 */
class ScalaClassImportParitySpec extends AbstractScalaTypeElementSpec {

    private static Object introspectionFor(ClassLoader classLoader, String className) {
        BeanIntrospector.forClassLoader(classLoader)
            .findIntrospection(classLoader.loadClass(className)).orElse(null)
    }

    @PendingFeature(reason = 'No introspection is produced for the imported type. The name '
        + 'resolves -- VisitorContext.getClassElement checks source classes before the classpath '
        + '-- so the gap is in whichever visitor calls VisitorUtils.collectImportedElements, or '
        + 'in the writer that names the generated introspection after the importing element')
    void "introspects a type named by ClassImport rather than annotated itself"() {
        when: 'Imported carries no @Introspected of its own'
        def classLoader = buildClassLoader('classimport.Importer', '''
package classimport

import io.micronaut.context.annotation.ClassImport

class Imported:
  var name: String = null
  var size: Int = 0

@ClassImport(classes = Array(classOf[Imported]))
class Importer
''')
        // Resolved by type rather than by generated name: the introspection is written for the
        // importing element, so its class name is not derived from the imported type.
        def introspection = introspectionFor(classLoader, 'classimport.Imported')

        then: 'the import is what makes it introspectable'
        introspection != null
        introspection.propertyNames.toList().sort() == ['name', 'size']
    }

    @PendingFeature(reason = 'Depends on @ClassImport producing an introspection at all, which '
        + 'is the pending case above')
    void "annotates an imported type through a mixin"() {
        when: 'the mixin declares a member of the same name and annotates it'
        def classLoader = buildClassLoader('classimport.Importer', '''
package classimport

import io.micronaut.context.annotation.ClassImport
import io.micronaut.context.annotation.Mixin
import io.micronaut.core.annotation.Introspected

class Imported:
  var name: String = null

@Mixin(Array(classOf[Imported]))
@Introspected
class ImportedMixin:
  @Introspected.Property(value = "renamed")
  var name: String = null

@ClassImport(classes = Array(classOf[Imported]))
class Importer
''')
        def introspection = introspectionFor(classLoader, 'classimport.Imported')

        then: 'the annotation written on the mixin reaches the imported type'
        introspection != null
        introspection.getProperty('renamed').present
    }
}
