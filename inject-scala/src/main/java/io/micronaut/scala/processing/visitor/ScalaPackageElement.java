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
package io.micronaut.scala.processing.visitor;

import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.PackageElement;

import java.util.Set;

/**
 * Scala package element backed by a package name.
 */
final class ScalaPackageElement extends AbstractScalaElement implements PackageElement {

    ScalaPackageElement(String packageName, ScalaVisitorContext visitorContext) {
        super(
            packageName,
            packageName,
            Set.of(),
            new MutableAnnotationMetadata(),
            visitorContext.getScalaAnnotationMetadataBuilder()
        );
    }

    @Override
    public String getSimpleName() {
        String name = getName();
        int index = name.lastIndexOf('.');
        if (index > -1) {
            return name.substring(index + 1);
        }
        return name;
    }

    @Override
    public boolean isProtected() {
        return false;
    }

    @Override
    public boolean isPublic() {
        return true;
    }
}
