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
package io.micronaut.scala.processing.test;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

/**
 * Low-order visitor used by Scala visitor-order tests.
 */
public final class ScalaLowOrderVisitor implements TypeElementVisitor<Object, Object> {

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        ScalaVisitorOrderRecorder.record("low-start");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        ScalaVisitorOrderRecorder.record("low-class:" + element.getName());
    }

    @Override
    public void finish(VisitorContext visitorContext) {
        ScalaVisitorOrderRecorder.record("low-finish");
    }
}
