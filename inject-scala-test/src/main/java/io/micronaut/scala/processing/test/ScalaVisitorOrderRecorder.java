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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared recorder for Scala visitor-order tests.
 */
public final class ScalaVisitorOrderRecorder {

    private static final ThreadLocal<Boolean> ENABLED = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> EVENTS = ThreadLocal.withInitial(ArrayList::new);

    private ScalaVisitorOrderRecorder() {
    }

    /**
     * Executes a compilation with visitor-order recording enabled.
     *
     * @param supplier The compilation work
     * @param <T> The result type
     * @return The supplier result
     */
    public static <T> T withRecording(Supplier<T> supplier) {
        ENABLED.set(Boolean.TRUE);
        EVENTS.set(new ArrayList<>());
        try {
            return supplier.get();
        } finally {
            ENABLED.remove();
            EVENTS.remove();
        }
    }

    /**
     * Records a visitor event.
     *
     * @param event The event
     */
    public static void record(String event) {
        if (Boolean.TRUE.equals(ENABLED.get())) {
            EVENTS.get().add(event);
        }
    }

    /**
     * Returns the recorded events.
     *
     * @return The recorded events
     */
    public static List<String> events() {
        return List.copyOf(EVENTS.get());
    }
}
