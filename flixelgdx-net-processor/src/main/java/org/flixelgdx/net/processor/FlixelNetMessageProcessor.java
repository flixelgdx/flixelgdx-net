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
package org.flixelgdx.net.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates reflection-free network codecs for classes annotated with {@code @NetMessage}.
 *
 * <p>For each annotated class {@code Foo} in package {@code p}, this emits {@code p.FooNetCodec},
 * an implementation of {@code FlixelNetCodec<Foo>} with a shared {@code INSTANCE}. The generated
 * code reads and writes each field directly, so it creates no garbage and works on platforms
 * without reflection. A class nested in another class gets the names of its enclosing classes in
 * front, so {@code Messages.Move} becomes {@code MessagesMoveNetCodec}.
 *
 * <p>The annotations are matched by name, so this processor has no compile dependency on the
 * extension; the source it produces references the extension's types, which are on the annotated
 * project's classpath.
 */
@SupportedAnnotationTypes("org.flixelgdx.net.message.NetMessage")
public final class FlixelNetMessageProcessor extends AbstractProcessor {

  private static final String MESSAGE = "org.flixelgdx.net.message.NetMessage";
  private static final String RANGE = "org.flixelgdx.net.message.NetRange";
  private static final String FLIXEL_STRING = "org.flixelgdx.util.FlixelString";
  private static final String SUFFIX = "NetCodec";
  private static final int MAX_FLOAT_BITS = 30;

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      if (!annotation.getQualifiedName().contentEquals(MESSAGE)) {
        continue;
      }
      for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
        if (element.getKind() == ElementKind.CLASS) {
          generate((TypeElement) element);
        } else {
          error(element, "@NetMessage can only be applied to a class.");
        }
      }
    }
    return true;
  }

  /** Writes the codec source file for one annotated class. */
  private void generate(TypeElement type) {
    String simpleName = type.getSimpleName().toString();
    if (!checkClass(type, simpleName)) {
      return;
    }

    String packageName = packageOf(type);
    String typeName = type.getQualifiedName().toString();
    String codecName = codecSimpleName(type);
    List<VariableElement> fields = new java.util.ArrayList<>();
    collectFields(type, fields);

    StringBuilder statics = new StringBuilder();
    StringBuilder layout = new StringBuilder("\"\"");
    StringBuilder write = new StringBuilder();
    StringBuilder read = new StringBuilder();
    StringBuilder reset = new StringBuilder();
    boolean ok = true;
    for (VariableElement field : fields) {
      ok &= appendField(type, field, statics, layout, write, read, reset);
    }
    if (!ok) {
      return;
    }

    String visibility = isPublic(type) ? "public " : "";
    StringBuilder src = new StringBuilder(2048);
    if (!packageName.isEmpty()) {
      src.append("package ").append(packageName).append(";\n\n");
    }
    src.append("import org.flixelgdx.net.FlixelNetBuffer;\n");
    src.append("import org.flixelgdx.net.FlixelNetException;\n");
    src.append("import org.flixelgdx.net.message.FlixelNetCodec;\n\n");
    src.append("/** Generated network codec for {@link ").append(typeName).append("}. Do not edit. */\n");
    src.append(visibility).append("final class ").append(codecName)
        .append(" implements FlixelNetCodec<").append(typeName).append("> {\n\n");
    src.append("  /** The one shared instance; add it to a FlixelNetProtocol. */\n");
    src.append("  public static final ").append(codecName).append(" INSTANCE = new ").append(codecName)
        .append("();\n\n");
    src.append("  /** What the message's bytes contain, used to detect builds that do not match. */\n");
    src.append("  public static final String LAYOUT = ").append(layout).append(";\n\n");
    src.append(statics);
    src.append("  private ").append(codecName).append("() {}\n\n");
    src.append("  @Override\n  public Class<").append(typeName).append("> getType() {\n    return ")
        .append(typeName).append(".class;\n  }\n\n");
    src.append("  @Override\n  public String getName() {\n    return \"")
        .append(processingEnv.getElementUtils().getBinaryName(type)).append("\";\n  }\n\n");
    src.append("  @Override\n  public String getLayout() {\n    return LAYOUT;\n  }\n\n");
    src.append("  @Override\n  public ").append(typeName).append(" create() {\n    return new ")
        .append(typeName).append("();\n  }\n\n");
    src.append("  @Override\n  public void reset(").append(typeName).append(" m) {\n").append(reset).append("  }\n\n");
    src.append("  @Override\n  public void write(").append(typeName).append(" m, FlixelNetBuffer out) {\n")
        .append(write).append("  }\n\n");
    src.append("  @Override\n  public void read(").append(typeName).append(" m, FlixelNetBuffer in) {\n")
        .append(read).append("  }\n");
    src.append("}\n");

    write(type, packageName, codecName, src.toString());
  }

  /** Reports every reason the class itself cannot be a message, and returns whether it can. */
  private boolean checkClass(TypeElement type, String simpleName) {
    boolean ok = true;
    Set<Modifier> mods = type.getModifiers();
    if (mods.contains(Modifier.ABSTRACT)) {
      error(type, "@NetMessage class '" + simpleName + "' must not be abstract.");
      ok = false;
    }
    if (mods.contains(Modifier.PRIVATE)) {
      error(type, "@NetMessage class '" + simpleName + "' must not be private.");
      ok = false;
    }
    Element enclosing = type.getEnclosingElement();
    if (enclosing.getKind() != ElementKind.PACKAGE) {
      if (!enclosing.getKind().isClass() && !enclosing.getKind().isInterface()) {
        error(type, "@NetMessage class '" + simpleName + "' must not be declared inside a method.");
        ok = false;
      } else if (!mods.contains(Modifier.STATIC)) {
        error(type, "@NetMessage class '" + simpleName + "' is nested in another class, so it must be static.");
        ok = false;
      }
    }
    boolean constructor = false;
    for (Element member : type.getEnclosedElements()) {
      if (member.getKind() == ElementKind.CONSTRUCTOR
          && ((ExecutableElement) member).getParameters().isEmpty()
          && !member.getModifiers().contains(Modifier.PRIVATE)) {
        constructor = true;
      }
    }
    if (!constructor) {
      error(type, "@NetMessage class '" + simpleName + "' needs a constructor with no parameters that is not private.");
      ok = false;
    }
    return ok;
  }

  /** Collects the fields to send, parent classes first, in declaration order. */
  private void collectFields(TypeElement type, List<VariableElement> out) {
    TypeMirror parent = type.getSuperclass();
    if (parent.getKind() == TypeKind.DECLARED) {
      TypeElement parentElement = (TypeElement) ((DeclaredType) parent).asElement();
      if (!parentElement.getQualifiedName().contentEquals("java.lang.Object")) {
        collectFields(parentElement, out);
      }
    }
    for (Element member : type.getEnclosedElements()) {
      if (member.getKind() != ElementKind.FIELD) {
        continue;
      }
      Set<Modifier> mods = member.getModifiers();
      if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.TRANSIENT)) {
        continue;
      }
      out.add((VariableElement) member);
    }
  }

  /**
   * Emits the code for one field into each of the method bodies being built.
   *
   * @return Whether the field is supported. An error has been reported when it is not.
   */
  private boolean appendField(TypeElement owner, VariableElement field, StringBuilder statics, StringBuilder layout,
      StringBuilder write, StringBuilder read, StringBuilder reset) {
    String name = field.getSimpleName().toString();
    String f = "m." + name;
    TypeMirror t = field.asType();
    Set<Modifier> mods = field.getModifiers();
    boolean isFinal = mods.contains(Modifier.FINAL);
    AnnotationMirror range = annotation(field, RANGE);

    if (mods.contains(Modifier.PRIVATE)) {
      return fail(field, "must not be private (the generated codec accesses it directly)");
    }
    boolean inPlace = isFlixelString(t) || isMessage(t);
    if (isFinal && !inPlace) {
      return fail(field, "must not be final; only FlixelString and nested @NetMessage fields can be");
    }
    if (range != null && !(t.getKind() == TypeKind.FLOAT || isRangedInteger(t.getKind()))) {
      return fail(field, "has @NetRange, which only works on float, byte, short, char and int fields");
    }

    String code;
    switch (t.getKind()) {
      case BOOLEAN -> {
        code = "z";
        write.append("    out.writeBool(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readBool();\n");
        reset.append("    ").append(f).append(" = false;\n");
      }
      case BYTE, SHORT, CHAR, INT -> {
        if (range != null) {
          code = appendRangedInteger(field, f, t, range, write, read);
          if (code == null) {
            return false;
          }
        } else {
          code = appendPlainInteger(f, t.getKind(), write, read);
        }
        reset.append("    ").append(f).append(" = 0;\n");
      }
      case LONG -> {
        code = "l";
        write.append("    out.writeVarLong(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readVarLong();\n");
        reset.append("    ").append(f).append(" = 0L;\n");
      }
      case FLOAT -> {
        if (range != null) {
          code = appendRangedFloat(field, f, range, write, read);
          if (code == null) {
            return false;
          }
        } else {
          code = "f";
          write.append("    out.writeFloat(").append(f).append(");\n");
          read.append("    ").append(f).append(" = in.readFloat();\n");
        }
        reset.append("    ").append(f).append(" = 0f;\n");
      }
      case DOUBLE -> {
        code = "d";
        write.append("    out.writeDouble(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readDouble();\n");
        reset.append("    ").append(f).append(" = 0.0;\n");
      }
      case ARRAY -> {
        code = appendArray(field, f, name, (ArrayType) t, write, read, reset);
        if (code == null) {
          return false;
        }
      }
      case DECLARED -> {
        code = appendDeclared(owner, field, f, name, t, isFinal, statics, write, read, reset);
        if (code == null) {
          return false;
        }
      }
      default -> {
        return fail(field, "has a type that cannot be sent: " + t);
      }
    }
    layout.append("\n    + \"").append(name).append(':').append(code).append(";\"");
    return true;
  }

  private String appendPlainInteger(String f, TypeKind kind, StringBuilder write, StringBuilder read) {
    switch (kind) {
      case BYTE -> {
        write.append("    out.writeByte(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readByte();\n");
        return "b";
      }
      case SHORT -> {
        write.append("    out.writeShort(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readShort();\n");
        return "s";
      }
      case CHAR -> {
        write.append("    out.writeShort(").append(f).append(");\n");
        read.append("    ").append(f).append(" = (char) in.readUShort();\n");
        return "c";
      }
      default -> {
        write.append("    out.writeVarInt(").append(f).append(");\n");
        read.append("    ").append(f).append(" = in.readVarInt();\n");
        return "i";
      }
    }
  }

  /** Emits a whole number sent as an offset from its smallest value, in just enough bits. */
  private String appendRangedInteger(VariableElement field, String f, TypeMirror t, AnnotationMirror range,
      StringBuilder write, StringBuilder read) {
    double minValue = number(range, "min");
    double maxValue = number(range, "max");
    if (minValue != Math.rint(minValue) || maxValue != Math.rint(maxValue)) {
      fail(field, "is a whole number, so its @NetRange min and max must be whole numbers too");
      return null;
    }
    long min = (long) minValue;
    long max = (long) maxValue;
    long lowest;
    long highest;
    switch (t.getKind()) {
      case BYTE -> {
        lowest = Byte.MIN_VALUE;
        highest = Byte.MAX_VALUE;
      }
      case SHORT -> {
        lowest = Short.MIN_VALUE;
        highest = Short.MAX_VALUE;
      }
      case CHAR -> {
        lowest = Character.MIN_VALUE;
        highest = Character.MAX_VALUE;
      }
      default -> {
        lowest = Integer.MIN_VALUE;
        highest = Integer.MAX_VALUE;
      }
    }
    if (max <= min) {
      fail(field, "has a @NetRange whose max is not greater than its min");
      return null;
    }
    if (min < lowest || max > highest) {
      fail(field, "has a @NetRange that does not fit in its type (" + t + ")");
      return null;
    }
    int bits = 64 - Long.numberOfLeadingZeros(max - min);
    write.append("    out.writeBits((int) (Math.max(").append(min).append("L, Math.min(").append(max)
        .append("L, (long) ").append(f).append(")) - ").append(min).append("L), ").append(bits).append(");\n");
    read.append("    ").append(f).append(" = (").append(t).append(") Math.min(").append(max)
        .append("L, (in.readBits(").append(bits).append(") & 0xFFFFFFFFL) + ").append(min).append("L);\n");
    return "i[" + min + "," + max + "]";
  }

  /** Emits a float sent as the nearest of evenly spaced steps across its range. */
  private String appendRangedFloat(VariableElement field, String f, AnnotationMirror range, StringBuilder write,
      StringBuilder read) {
    double min = number(range, "min");
    double max = number(range, "max");
    int bits = (int) number(range, "bits");
    if (Double.isNaN(min) || Double.isInfinite(min) || Double.isNaN(max) || Double.isInfinite(max) || !(max > min)) {
      fail(field, "has a @NetRange whose max is not greater than its min");
      return null;
    }
    if (bits < 1 || bits > MAX_FLOAT_BITS) {
      fail(field, "has a @NetRange whose bits is not from 1 to " + MAX_FLOAT_BITS);
      return null;
    }
    String args = "(float) " + min + ", (float) " + max + ", " + bits;
    write.append("    out.writeQuantized(").append(f).append(", ").append(args).append(");\n");
    read.append("    ").append(f).append(" = in.readQuantized(").append(args).append(");\n");
    return "f[" + min + "," + max + "," + bits + "]";
  }

  /** Emits an array of primitives, sent as its length followed by its elements. */
  private String appendArray(VariableElement field, String f, String name, ArrayType t, StringBuilder write,
      StringBuilder read, StringBuilder reset) {
    TypeMirror component = t.getComponentType();
    TypeKind kind = component.getKind();
    if (!kind.isPrimitive()) {
      fail(field, "is an array of " + component + "; only arrays of primitives can be sent");
      return null;
    }
    String writeElement;
    String readElement;
    String zero = "0";
    String code;
    switch (kind) {
      case BOOLEAN -> {
        writeElement = "out.writeBool(" + f + "[i])";
        readElement = "in.readBool()";
        zero = "false";
        code = "z";
      }
      case BYTE -> {
        writeElement = "out.writeByte(" + f + "[i])";
        readElement = "in.readByte()";
        zero = "(byte) 0";
        code = "b";
      }
      case SHORT -> {
        writeElement = "out.writeShort(" + f + "[i])";
        readElement = "in.readShort()";
        zero = "(short) 0";
        code = "s";
      }
      case CHAR -> {
        writeElement = "out.writeShort(" + f + "[i])";
        readElement = "(char) in.readUShort()";
        zero = "(char) 0";
        code = "c";
      }
      case INT -> {
        writeElement = "out.writeVarInt(" + f + "[i])";
        readElement = "in.readVarInt()";
        code = "i";
      }
      case LONG -> {
        writeElement = "out.writeVarLong(" + f + "[i])";
        readElement = "in.readVarLong()";
        zero = "0L";
        code = "l";
      }
      case FLOAT -> {
        writeElement = "out.writeFloat(" + f + "[i])";
        readElement = "in.readFloat()";
        zero = "0f";
        code = "f";
      }
      default -> {
        writeElement = "out.writeDouble(" + f + "[i])";
        readElement = "in.readDouble()";
        zero = "0.0";
        code = "d";
      }
    }
    // Every element takes at least one byte, or one bit for a boolean, so an honest length can
    // never exceed what is left in the packet. Checking that stops a hostile length from making
    // the game allocate a huge array.
    String most = kind == TypeKind.BOOLEAN ? "in.remaining() * 8L" : "in.remaining()";
    write.append("    if (").append(f).append(" == null) {\n      out.writeVarUInt(0);\n    } else {\n");
    write.append("      out.writeVarUInt(").append(f).append(".length);\n");
    write.append("      for (int i = 0; i < ").append(f).append(".length; i++) {\n        ").append(writeElement)
        .append(";\n      }\n    }\n");
    read.append("    {\n      int n = in.readVarUInt();\n");
    read.append("      if (n < 0 || n > ").append(most).append(") {\n");
    read.append("        throw new FlixelNetException(\"Array '").append(name)
        .append("' is longer than the packet it came in.\");\n      }\n");
    read.append("      if (").append(f).append(" == null || ").append(f).append(".length != n) {\n        ").append(f)
        .append(" = new ").append(component).append("[n];\n      }\n");
    read.append("      for (int i = 0; i < n; i++) {\n        ").append(f).append("[i] = ").append(readElement)
        .append(";\n      }\n    }\n");
    reset.append("    if (").append(f).append(" != null) {\n      java.util.Arrays.fill(").append(f).append(", ")
        .append(zero).append(");\n    }\n");
    return "[" + code;
  }

  /** Emits a String, FlixelString, enum, or nested message field. */
  private String appendDeclared(TypeElement owner, VariableElement field, String f, String name, TypeMirror t,
      boolean isFinal, StringBuilder statics, StringBuilder write, StringBuilder read, StringBuilder reset) {
    TypeElement element = (TypeElement) ((DeclaredType) t).asElement();
    String typeName = element.getQualifiedName().toString();
    if (typeName.equals("java.lang.String")) {
      write.append("    out.writeString(").append(f).append(" == null ? \"\" : ").append(f).append(");\n");
      read.append("    ").append(f).append(" = in.readString();\n");
      reset.append("    ").append(f).append(" = null;\n");
      return "S";
    }
    if (typeName.equals(FLIXEL_STRING)) {
      write.append("    if (").append(f)
          .append(" == null) {\n      out.writeVarUInt(0);\n    } else {\n      out.writeString(")
          .append(f).append(");\n    }\n");
      if (!isFinal) {
        read.append("    if (").append(f).append(" == null) {\n      ").append(f).append(" = new ")
            .append(FLIXEL_STRING)
            .append("();\n    }\n");
      }
      read.append("    in.readString(").append(f).append(");\n");
      reset.append("    if (").append(f).append(" != null) {\n      ").append(f).append(".clear();\n    }\n");
      return "S";
    }
    if (element.getKind() == ElementKind.ENUM) {
      int count = 0;
      for (Element member : element.getEnclosedElements()) {
        if (member.getKind() == ElementKind.ENUM_CONSTANT) {
          count++;
        }
      }
      if (count == 0) {
        fail(field, "is an enum with no constants");
        return null;
      }
      int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(count - 1));
      String values = "VALUES_" + name;
      statics.append("  private static final ").append(typeName).append("[] ").append(values).append(" = ")
          .append(typeName).append(".values();\n\n");
      write.append("    out.writeBits(").append(f).append(" == null ? 0 : ").append(f).append(".ordinal(), ")
          .append(bits)
          .append(");\n");
      read.append("    {\n      int o = in.readBits(").append(bits).append(");\n      if (o >= ").append(values)
          .append(".length) {\n        throw new FlixelNetException(\"Field '").append(name)
          .append("' has no enum constant number \" + o + \".\");\n      }\n      ").append(f).append(" = ")
          .append(values)
          .append("[o];\n    }\n");
      reset.append("    ").append(f).append(" = null;\n");
      return "e" + count;
    }
    if (isMessage(t)) {
      if (element.equals(owner)) {
        fail(field, "has the same type as the class it is in, which would never end");
        return null;
      }
      String pkg = packageOf(element);
      String codec = (pkg.isEmpty() ? "" : pkg + ".") + codecSimpleName(element);
      write.append("    if (").append(f).append(" == null) {\n      throw new IllegalStateException(\"Field '")
          .append(name)
          .append("' of ").append(owner.getSimpleName())
          .append(" is null; a nested message must be set before sending.\");\n    }\n");
      write.append("    ").append(codec).append(".INSTANCE.write(").append(f).append(", out);\n");
      if (!isFinal) {
        read.append("    if (").append(f).append(" == null) {\n      ").append(f).append(" = ").append(codec)
            .append(".INSTANCE.create();\n    }\n");
      }
      read.append("    ").append(codec).append(".INSTANCE.read(").append(f).append(", in);\n");
      reset.append("    if (").append(f).append(" != null) {\n      ").append(codec).append(".INSTANCE.reset(")
          .append(f)
          .append(");\n    }\n");
      return "{\" + " + codec + ".LAYOUT + \"}";
    }
    fail(field, "has a type that cannot be sent: " + typeName);
    return null;
  }

  private boolean isRangedInteger(TypeKind kind) {
    return kind == TypeKind.BYTE || kind == TypeKind.SHORT || kind == TypeKind.CHAR || kind == TypeKind.INT;
  }

  private boolean isFlixelString(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals(FLIXEL_STRING);
  }

  private boolean isMessage(TypeMirror t) {
    return t.getKind() == TypeKind.DECLARED && annotation(((DeclaredType) t).asElement(), MESSAGE) != null;
  }

  /** Returns whether the class and every class it is nested in are public. */
  private boolean isPublic(TypeElement type) {
    Element current = type;
    while (current.getKind() != ElementKind.PACKAGE) {
      if (!current.getModifiers().contains(Modifier.PUBLIC)) {
        return false;
      }
      current = current.getEnclosingElement();
    }
    return true;
  }

  /** Returns the annotation with the given class name on an element, or null if it has none. */
  private AnnotationMirror annotation(Element element, String name) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(name)) {
        return mirror;
      }
    }
    return null;
  }

  /** Returns one number from an annotation, using the annotation's default when it was not set. */
  private double number(AnnotationMirror mirror, String key) {
    Map<? extends ExecutableElement, ? extends AnnotationValue> values = processingEnv.getElementUtils()
        .getElementValuesWithDefaults(mirror);
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals(key)) {
        return ((Number) entry.getValue().getValue()).doubleValue();
      }
    }
    return Double.NaN;
  }

  /** Returns the simple name of the codec generated for a message class. */
  private String codecSimpleName(TypeElement type) {
    StringBuilder name = new StringBuilder(type.getSimpleName());
    Element current = type.getEnclosingElement();
    while (current.getKind() != ElementKind.PACKAGE) {
      name.insert(0, current.getSimpleName());
      current = current.getEnclosingElement();
    }
    return name.append(SUFFIX).toString();
  }

  private String packageOf(TypeElement type) {
    return processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
  }

  private void write(TypeElement origin, String packageName, String codecName, String source) {
    String fqn = packageName.isEmpty() ? codecName : packageName + "." + codecName;
    try {
      JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, origin);
      try (Writer writer = file.openWriter()) {
        writer.write(source);
      }
    } catch (IOException e) {
      error(origin, "Could not write " + fqn + ": " + e.getMessage());
    }
  }

  private boolean fail(VariableElement field, String problem) {
    error(field, "@NetMessage field '" + field.getSimpleName() + "' " + problem + ".");
    return false;
  }

  private void error(Element element, String message) {
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
