/*
 * MIT License
 *
 * Copyright (c) 2026 stringdotjar
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.flixelgdx.net.message;

import org.flixelgdx.net.FlixelNetBuffer;
import org.flixelgdx.util.FlixelString;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a message that can be sent over the network.
 *
 * <p>Think of a message as a paper form. The class says which boxes the form has (its fields),
 * and each message that is sent is one filled-in copy. At compile time an annotation processor
 * writes the code that copies each box into a {@link FlixelNetBuffer} and back out again, so
 * there is no reflection and nothing to write by hand. For a class named {@code Foo} it generates
 * a companion named {@code FooNetCodec} in the same package, which is what gets added to a
 * {@link FlixelNetProtocol}.
 *
 * <pre>{@code
 * @NetMessage
 * public class MoveInput {
 *   @NetRange(min = -1, max = 1, bits = 8)
 *   public float dx;
 *   @NetRange(min = -1, max = 1, bits = 8)
 *   public float dy;
 *   public boolean jump;
 * }
 *
 * // Once, at startup:
 * FlixelNetProtocol protocol = new FlixelNetProtocol("my-game", 1)
 *   .add(MoveInputNetCodec.INSTANCE);
 * }</pre>
 *
 * <p>To turn the processor on, add it to the game's build next to the extension itself:
 *
 * <pre>{@code
 * annotationProcessor("org.flixelgdx:flixelgdx-net-processor:<version>")
 * }</pre>
 *
 * <h2>Rules for a message class</h2>
 *
 * <ul>
 *   <li>It must not be abstract, and it needs a constructor with no parameters that is not
 *       private. A class nested inside another class must be {@code static}.</li>
 *   <li>Every field is sent, in the order the fields are written in the class (fields of a parent
 *       class go first), except {@code static} and {@code transient} fields. Fields must not be
 *       private, because the generated code reads and writes them directly.</li>
 *   <li>Supported field types are the primitives ({@code boolean}, {@code byte}, {@code short},
 *       {@code char}, {@code int}, {@code long}, {@code float}, {@code double}), {@link String},
 *       {@link FlixelString}, enums, other {@code @NetMessage} classes, and arrays of primitives.</li>
 *   <li>A {@code final} field is only allowed for a {@link FlixelString} or another
 *       {@code @NetMessage} class, which are filled in place instead of being replaced.</li>
 * </ul>
 *
 * <h2>How fields are stored</h2>
 *
 * <ul>
 *   <li>A {@code boolean} takes one bit. Put booleans next to each other so they share a byte.</li>
 *   <li>An {@code int} or {@code long} takes fewer bytes the closer it is to zero.</li>
 *   <li>An enum takes only as many bits as it needs to tell its constants apart.</li>
 *   <li>A {@code float} takes four bytes, or far fewer with {@link NetRange}.</li>
 *   <li>A {@link String} creates a new object every time it is received. For text that arrives
 *       often, use a {@code final} {@link FlixelString} field instead, which is reused.</li>
 * </ul>
 *
 * <p>Messages are reused after they are sent or handled, so never keep a reference to one. Copy
 * out the values that are needed.
 *
 * @see NetRange
 * @see FlixelNetProtocol
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NetMessage {
}
