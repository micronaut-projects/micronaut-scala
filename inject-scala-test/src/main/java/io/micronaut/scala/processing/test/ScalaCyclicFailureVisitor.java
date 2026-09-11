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
 * Test visitor that fails with a cause chain containing a cycle deeper than its head, to
 * exercise the guard in the engine's message extraction.
 */
public final class ScalaCyclicFailureVisitor implements TypeElementVisitor<Object, Object> {

    private static final ThreadLocal<Boolean> ENABLED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Runs a compilation in which this visitor fails on start.
     *
     * @param runnable The compilation
     */
    public static void failing(Runnable runnable) {
        ENABLED.set(Boolean.TRUE);
        try {
            runnable.run();
        } finally {
            ENABLED.remove();
        }
    }

    @Override
    public void start(VisitorContext visitorContext) {
        if (!ENABLED.get()) {
            return;
        }
        // a -> b -> c -> b. Only comparing against the head never reaches `a` again, so a
        // walk without a visited set runs forever.
        RuntimeException head = new RuntimeException((String) null);
        RuntimeException middle = new RuntimeException((String) null);
        RuntimeException tail = new RuntimeException((String) null);
        head.initCause(middle);
        middle.initCause(tail);
        tail.initCause(middle);
        throw head;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        // Nothing to do: the failure happens in start().
    }
}
