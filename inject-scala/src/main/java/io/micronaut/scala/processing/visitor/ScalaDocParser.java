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

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a Scaladoc comment into the prose description and the block tags beneath it.
 *
 * <p>What reaches here is the raw comment as the compiler kept it, delimiters and all --
 * dotty stores {@code Comment.raw}, unlike javac's {@code getDocComment} and KSP's
 * {@code docString}, which hand back the content already stripped. So the markers come off
 * here first, and Scala has one of its own: a continuation line may be aligned under the
 * opening {@code /**} with its asterisk in a different column, which is the common style in
 * Scala sources and is not a thing Javadoc has to cope with.</p>
 *
 * <p>Tags are the Javadoc ones. Scaladoc adds its own vocabulary ({@code @tparam},
 * {@code @note}, {@code @example}) and none of it changes the shape: a line beginning with
 * {@code @word} opens a tag that runs until the next one, and {@code @param} is followed by
 * the name it documents.</p>
 */
final class ScalaDocParser {

    private ScalaDocParser() {
    }

    /**
     * The prose description, with the block tags removed.
     *
     * @param raw The raw comment
     * @return The description, or empty when the comment is only tags
     */
    static Optional<String> description(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        var description = new StringBuilder();
        for (String line : strip(raw)) {
            if (isTagLine(line)) {
                break;
            }
            if (!description.isEmpty()) {
                description.append('\n');
            }
            description.append(line);
        }
        String text = description.toString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * The text of the {@code @param} tag documenting the given name.
     *
     * <p>This is where a Scala case class's property documentation lives. The parameters of a
     * case class are its properties, and they are documented the way a Java record's are --
     * on the class, as {@code @param} tags -- so a configuration property's description is
     * read from the declaring type rather than from anything attached to the parameter
     * itself.</p>
     *
     * @param raw The raw comment
     * @param name The parameter name
     * @return The documentation for that parameter, if the comment gives any
     */
    static Optional<String> parameter(@Nullable String raw, String name) {
        if (raw == null) {
            return Optional.empty();
        }
        var content = new StringBuilder();
        boolean open = false;
        for (String line : strip(raw)) {
            if (isTagLine(line)) {
                if (open) {
                    break;
                }
                String rest = tagContent(line, "@param");
                if (rest != null) {
                    String[] split = rest.split("\\s+", 2);
                    if (split[0].equals(name)) {
                        open = true;
                        if (split.length > 1) {
                            content.append(split[1]);
                        }
                    }
                }
            } else if (open && !line.isBlank()) {
                if (!content.isEmpty()) {
                    content.append(' ');
                }
                content.append(line.trim());
            }
        }
        String text = content.toString().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /**
     * Removes the comment delimiters and the leading asterisk of each line.
     *
     * @param raw The raw comment
     * @return The content lines, in order
     */
    private static List<String> strip(String raw) {
        String body = raw.trim();
        if (body.startsWith("/**")) {
            body = body.substring(3);
        } else if (body.startsWith("/*")) {
            body = body.substring(2);
        }
        if (body.endsWith("*/")) {
            body = body.substring(0, body.length() - 2);
        }
        var lines = new ArrayList<String>();
        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
            }
            // Only the one space that separates the asterisk from the text; indentation
            // inside a code block is the author's and is left alone.
            if (trimmed.startsWith(" ")) {
                trimmed = trimmed.substring(1);
            }
            lines.add(trimmed);
        }
        return lines;
    }

    private static boolean isTagLine(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '@') {
            return false;
        }
        return Character.isLetter(trimmed.charAt(1));
    }

    private static @Nullable String tagContent(String line, String tag) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(tag)) {
            return null;
        }
        String rest = trimmed.substring(tag.length());
        if (rest.isEmpty() || !Character.isWhitespace(rest.charAt(0))) {
            return null;
        }
        return rest.trim();
    }
}
