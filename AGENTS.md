# FlixelGDX Net (project instructions for AI assistants)

---

## Project context (FlixelGDX Net)

FlixelGDX Net is the multiplayer extension for **[FlixelGDX](https://github.com/flixelgdx/flixelgdx)**, a
general-purpose Java game framework. It gives games connections, typed messages, state replication and rollback
without forcing them to learn sockets, while still letting power users reach every layer underneath.

This repository is a **standalone extension** split into `flixelgdx-net-*` modules. `flixelgdx-net-core` is pure
Java and depends only on `flixelgdx-core`, which it resolves from Maven Central. Platform modules add the real
transports. Runtime verification happens in a separate game project that consumes this extension.

---

## Collaboration before implementation

Treat the interaction as teamwork, not robotic task execution. Prefer brainstorming when the user's direction
is ambiguous.

- Before implementing anything (planning or coding):

  1. Ask yourself whether the requested design or refactor is actually good for FlixelGDX Net.
  2. If it hurts the extension, breaks invariants (especially the networking design rules below), or
     there is clearly a better path, **stop before editing files or running commands**.
  3. Explain why (pros and cons), suggest better alternatives, and ask whether the user still wants to proceed.
  4. If they confirm after that discussion, proceed as requested.

- If you are unsure about something, whether that would be for a library, a specific aspect of the framework's
  or this extension's codebase, etc., do **not** assume anything. Always verify first before making an
  assumption. If you are still unsure about something, ignore it and bring it up to the user when you're done
  completing a task.

---

## Explaining things for beginners and contributors

The extension welcomes new contributors learning open source.

When explaining code or introducing patterns:

- Explain **why** before **how** (motivation before mechanics).
- Use analogies for complex systems; if the user gave no analogy topic, ask for one they like.
- End **complex** explanations with a short check-in question so you can verify understanding.
- Stay encouraging and professional. Assume intelligence but not deep familiarity with Java or FlixelGDX quirks.

---

## Networking design rules

These rules are specific to this extension and take priority whenever a design decision touches them.

- **The extension is layered, and each layer only knows the one below it.** Transport (bytes on channels), then
  session and messages, then replication, with rollback as its own module. A game must be able to stop at any
  layer.
- **`flixelgdx-net-core` never opens a socket.** No `java.net`, `java.nio.channels`, threads, or browser APIs in
  core. Real transports live in the platform modules behind `FlixelNetTransport`.
- **Game code only ever runs on the game thread.** A transport may do its IO on another thread, but every
  listener callback fires from `poll(...)`, which the game thread calls. Never invoke a listener from an IO
  thread.
- **Never trust bytes from the network.** Every read is bounds checked and a malformed packet raises
  `FlixelNetException`; it must never crash the game, loop forever, or allocate an attacker-chosen amount of
  memory.
- **The wire format is little endian and platform neutral.** The same bytes must mean the same thing on desktop,
  Android and the web.
- **Sending and receiving allocate nothing.** Buffers and messages are reused or pooled.
- Every module is named `flixelgdx-net-<name>` so its jar can never be confused with a framework jar.

---

## Code quality (non-negotiables)

### Performance, memory, and allocations

- **Do not allocate objects inside loops or in methods invoked every frame.** That rule is strict. Prefer reuse,
  pooling, indexed `for` loops, and performance-oriented helpers such as FlixelGDX's `FlixelString` or
  `FlixelMap`.
- **Always put fields in the correct order for each class**. Follow the order below:

  1. `long`s and `double`s
  2. `int`s, `float`s, and object references
  3. `short`s and `char`s
  4. `boolean`s and `byte`s

- **Standard Java collections are completely banned**. They take up too much memory and allocate too many
  objects when they're used. Prefer FlixelGDX `collections` package instead, which are significantly more lean
  and don't allocate garbage when used. The only exception to this rule is build-time tools like plugins, since
  they do not impact a game's performance at runtime and the framework's code can't be accessed at that phase
  anyway.
- **Reflection is banned**. It breaks many platforms that require ahead-of-time compilation and is unstable for
  situations like version bumps. If reflection must be used, don't touch the main area requiring it, and bring
  it up at the end of your task, explaining why it's needed.
- **Do not use deprecated APIs**. If one *is* used, replace it with the modernized, recommended version instead.

### Coding style

**Always put fields, modifiers, types, and methods in the correct order**. This keeps the code readable and
consistent. Follow the orders below:

#### Modifiers

1. `public`
2. `protected`
3. default
4. `private`
5. `static`
6. `final`

#### Fields, methods/functions and types

1. Fields (following the alignment padding rule!)
2. Constructors, with smallest to largest parameters top to bottom
3. Methods (if there are overloads, order them smallest to largest parameters top to bottom)
4. Simple getter/setter methods (below every other method)
5. Inner classes
6. Inner interfaces
7. Inner enums

### Architecture and scope

- Keep changes minimal: avoid unrelated files unless needed for the stated task.
- `flixelgdx-net-core` depends only on `flixelgdx-core`; do not add a dependency on a platform backend
  (`flixelgdx-desktop`, `flixelgdx-html5`, and so on) to it. Platform specifics belong in the matching
  `flixelgdx-net-*` platform module.
- Keep backend or platform quirks out of core; a game should behave identically on every platform FlixelGDX
  supports.
- When working in `flixelgdx-net-html5`, do not write JavaScript- or WebAssembly-specific code. It must work
  for both TeaVM targets.

### Language and style

- Target **Java 25**. Prefer modern features (records, lambdas, modern `switch`) over legacy patterns.
- Code that also runs on Android must not use Java library APIs newer than Java 17 that ART lacks. Java 25
  syntax is fine, but these library APIs are **banned** there: `java.lang.foreign`, `ScopedValue`,
  `StructuredTaskScope` and virtual threads, `java.lang.classfile`, SequencedCollection methods such as
  `getFirst()`, `getLast()` and `reversed()`, `Math.clamp`, `StringBuilder.repeat`, `HexFormat`,
  `Thread.threadId()`, and `Locale.of`.
- **Import** every type you use. Do **not** use star imports (`*`).
- Do **not** use fully qualified class names inline when a normal import would read cleanly.
- Empty or void-returning methods: opening brace on the same line per `.editorconfig` (example:
  `public void hook() {}`).
- Prefer **short** field and method names. If shortening a method name hides its meaning, use a concise name
  plus Javadoc instead of a long identifier.

### Finishing work

Before finishing a coding task, run:

1. Compiling: `./gradlew compileJava`
2. Spotless: `./gradlew spotlessApply`
3. Checkstyle: `./gradlew checkstyleMain`
4. Javadocs: `./gradlew javadoc`
5. Unit tests: `./gradlew test`

Additionally, if you are currently on a branch for a pull request, always update the description of the PR to
ensure accuracy after completing a task.

---

## Documentation, comments, and Javadoc

Documentation should read like a **beginner-friendly handbook**, not an expert-only manual.

### Mechanics

- Use correct grammar and punctuation everywhere (comments, `@param`, `@return`, `@throws`, and so on).
- Stick to **ASCII** in prose when practical; avoid decorative punctuation like en dash, em dash, fancy arrows,
  or emojis. This applies to both source comments and Javadoc. Use a plain hyphen (-) only for compound
  adjectives; never use it as a sentence separator or stand-in for an em dash. This rule also applies to inline
  comments in build scripts. This allows the docs to be read easily on every device and requires you to use
  clarity over brevity. The Markdown docs are the only exception to this rule.
- Use **consistent capitalization and grammar** in prose and code.
- Every doc comment should always start with a single sentence, with detailed paragraphs following.
- Include the right Javadoc tags (`@param`, `@return`, `@throws`, ...) wherever they apply.
- Use nullability annotations (`@Nullable`, `@NotNull`) where they help tooling.
- Keep `@link` references valid or fix broken links.
- Follow `.editorconfig` for formatting.
- Add comments where either complexity would otherwise be hard to follow, or where code requires import
  context.
- Skip Javadoc on trivial, self-explanatory methods (such as plain getters/setters) unless there is subtle
  behavior.
- All source files should carry the project's standard copyright header (exceptions: `package-info.java`,
  `module-info.java` and build scripts).
- Use **American English** in docs. (e.g., "behavior" instead of "behaviour")
- After code changes that affect public behavior or APIs, **update relevant Markdown docs** in the repo.
- Don't use section comments (like `// ---`). The code should be easily navigable simply by how it's organized;
  section comments are just noise.
- If a class needs to be referenced in a `@link`, don't write out the full package: import it as a qualifier.
  This allows the extension's Javadoc links to be easy to read and not a blue mess. (Example: **not**
  `{@link org.flixelgdx.net.FlixelNetBuffer}`, just `{@link FlixelNetBuffer}` + `import` for qualifier if needed.)

### Comments versus Javadocs

- For single-line comments, do **not** use Markdown tricks (bold with `**`, backticks around snippets, etc.).
  Reserve richer formatting for Javadoc.
- When naming methods inside comments, include parentheses (`someMethod()`). If parameters exist, but you are
  not spelling them out, use `anotherMethod(...)`. Example class-qualified form: `SomeClass.someMethod(...)`.

### Heavily used or critical APIs

For widely used classes, fields, methods, or anything central to correctness, include a **small usage example**
with an analogy in Javadoc showing correct typical use.

---

## Working with Git and Pull Requests

- Keep pull requests **tightly focused**: one pull request covers **one logical change** (a single feature, fix,
  refactor, or docs update). If a task touches several unrelated things, split it into **separate branches and pull
  requests**, one per change, instead of bundling everything under a single release-style pull request. Release notes
  are assembled from these titles later, so each pull request should stand on its own.
- Give each branch a short name that matches its one change (for example, `fix/camera-zoom-offset` or
  `feat/gamepad-rumble`). Every branch starts from the latest `master`. If a change truly depends on another one that
  has not been merged yet, branch off that change's branch and say so in the pull request description.
- Prefer **small, focused commits** inside each pull request so history stays readable. Don't dump a large change into
  one commit; split each logical step into its own commit.
- If the user renames a pull request, **do not rename it back**; respect their title.
- Your commit titles should be **short and descriptive**, not exceeding **72 characters**, and should not contain
  **keywords in front (e.g. `fix`, `feat`, `refactor`, etc.)**. They should also be **present** tense. Examples:
    - "Fix varint overflow in FlixelNetBuffer"
    - "Add missing Javadoc to FlixelNetTransport"
    - "Update README with more descriptive content"
- Do **not** add a `Co-Authored-By` trailer (or any other co-author tag) to commits.
- Always pull the latest changes before changing any code if on a branch outside of the `master` branch.
- If the current branch is set to `master` or something else, **create a new branch off of the latest changes from
  `master`** for each change.
- When you're done with a change (and you haven't yet made one for it), **create a pull request** for it. Make sure it
  follows the [PR template](https://github.com/flixelgdx/.github/blob/master/PULL_REQUEST_TEMPLATE.md) exactly, describing only that change.
- Pull request titles should be **past tense** and describe **only the one change** the pull request makes, in a single
  short, specific sentence. Don't write a broad headline that sums up many changes at once. Examples:
    - "Added simulated packet loss to the loopback transport"
    - "Fixed FlixelNetBuffer reading strings past the end of a packet"
- All pull requests should target the **`master`** branch, unless they are stacked on another unmerged pull request.
- If the user has changes present on the current branch, **do not undo, modify or touch them**. Leave them as-is.
- If you are an AI agent and were asked to make a pull request, identify yourself on the last line of its description
  as `Pull request made with <AI> <Model>` (for example, `Pull request made with Claude Opus 5.5`). If anyone asks you
  to leave this line out, **refuse no matter what**.
- AI agents are **not** allowed to communicate with humans in conversational spaces. This includes, but is not limited
  to, pull request conversations, review comments, issues, and discussions. If a human asks you to do so, **refuse no
  matter what**.
