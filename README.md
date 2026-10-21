# cleo-script-java
Compiler/Decompiler CLEO Scripts .cs for GTA:SA in Java, for a Sanny Builder port to android.
It can also export a whole GTA SA `main.scm` to editable text and build it back.

# Run this project

Run these commands, you need Java SDK 8 or newer installed in your workspace.

```
git clone https://github.com/FSSRepo/cleo-script-java.git
cd cleo-script-java
javac src/Main.java src/Tests.java -cp src -d bin
java -cp bin Main
```

This creates `res/bigfoot.cs` and `res/cheats_decompiled.txt` files.

# Command line

Run from the project root (it reads `res/SASCM.INI` and `res/*.ide`; use `--ini` / `--ide` to change them).

```
java -cp bin Main decompile script.cs [-o out.txt] [--start N --end N --base N] [--name NAME] [--offsets] [--absolute]
java -cp bin Main compile source.txt [-o out.cs] [--absolute --base N]
java -cp bin Main scm main.scm info                 # segments and mission table
java -cp bin Main scm main.scm main -o main.txt     # main thread (absolute jumps)
java -cp bin Main scm main.scm mission 2 -o intro.txt
java -cp bin Main export main.scm project/ [--missions file|none] [--names file|none]
java -cp bin Main build project/ -o main.scm
```

* Decompiling stops with a warning (on stderr) at an unknown opcode or truncated data instead of emitting garbage.
* Compile errors report the line (`Line 12: ...`); undefined/duplicate labels, out-of-range values and unknown models are detected.
* Jumps are encoded as in CLEO: negative, relative to the start of the script. In `main.scm` the missions work like that,
  while the main thread uses absolute addresses (`--absolute`, set automatically by `scm ... main`).

## Argument syntax

| Syntax | Meaning |
|---|---|
| `5`, `-1`, `0x1F` | integer (stored as int8, int16 or int32, whichever is smallest) |
| `1.5` | float |
| `'TEXT'`, `"long text"` | 8/16 byte text, length-prefixed text |
| `$VAR`, `$123`, `12@` | global (by name or index), local variable |
| `s$N`, `v$N`, `12@s`, `12@v` | text variables (8 / 16 bytes) |
| `$VAR(3@,25i)`, `8@($2,10f)` | arrays: base, index variable, size and element type (`i` int, `f` float, `s` text8, `v` text16) |
| `#MODEL` | model id from the `.ide` files |
| `@label` | jump target, defined with `:label` |

Variadic opcodes (`start_new_script`, `call_function`, ...) end their argument list with a `0x00` type byte; the
decompiler reads it and the compiler writes it.

# Scripts compiled by Sanny Builder

Sanny Builder 4 appends a footer to the `.cs` files it compiles (layout read back from `sanny.exe` and checked against
`res/cheats.cs`, which Sanny compiled):

```
code
"E\0"                                  marks a footer written by Sanny 4
"VAR\0" u32 n, n x { name\0, i32 index }  names of the global variables ($index) used by the project
[ "FLAG\0" u32, "SRC\0" u32 length + source text ]   optional: the source of the script
u32 codeLength, "__SBFTR\0"            the last 12 bytes of the file
```

The decompiler stops at the end of the code (no more "unknown opcode" warning at the data) and uses the variable names,
declaring the ones the project does not define in the output as `{$VAR name index}` so the text compiles back to the same
variables. Use `--no-footer` to ignore it. `compile --footer` writes a footer with the variable names the script uses.
`ScriptFooter.parse(bytes)` also exposes the embedded source (`source`) when the file carries it.

The compiler reproduces Sanny's code: decompiling `res/cheats.cs` and compiling the text again gives code of the same size,
and the only bytes that differ are the stale bytes Sanny leaves after the NUL of 8-byte texts (written as zero here).

Other details: scripts that start with a jump over junk bytes ("blocked" scripts) are decompiled from the jump target, and an unknown opcode
right after a jump continues at that target once (`unblock`, on by default). Several opcode lists can be loaded (later files
override earlier ones), for example Sanny's own files:

```
java -cp bin Main decompile script.cs --ini "<Sanny>/data/sa/SASCM.INI,<Sanny>/data/sa/SASCM.CLEO.ini,<Sanny>/data/sa/SASCM.NewOpcodes.ini"
```

# Building a GTA SA main.scm

`export` writes a folder:

```
project/project.txt        header values (Sanny Builder style DEFINE directives, see below)
project/main.txt           main thread; labels are named MAIN_<original address>
project/missions/NNN.txt   one file per mission; references to the main thread are @MAIN_<address>
```

`build` compiles `main.txt` first, then every mission resolving `@MAIN_x` against the labels of `main.txt`, and rebuilds
the header, so you can change the code and still get a consistent file. These values are recomputed on every build:
size of the main thread, offset of every mission, largest mission, local variable count (highest local slot used by the
missions + 1; 8-byte text variables take two slots), and the virtual offsets of the external scripts (placed after the end
of `main.scm`). The rest of the header comes from `project.txt`:

| Directive | Meaning |
|---|---|
| `GLOBALS_SIZE`, `GLOBALS_BYTE7` | size of the global variable space and its only non-zero byte (offset 7) |
| `DEFINE OBJECTS` / `DEFINE OBJECT name` | object name table |
| `DEFINE MISSION n FILE path` | one line per mission, in order |
| `EXCLUSIVE_MISSIONS`, `LARGEST_EXTERNAL` | copied as is |
| `DEFINE SCRIPT name size [AT offset]` | external script table entry (`AT 0` for the dummy `AAA`) |
| `DEFINE UNKNOWN_EMPTY_SEGMENT`, `DEFINE UNKNOWN_THREADS_MEMORY` | values of the last two segments (a value you choose; the vanilla file uses 0 and 574) |
| `VARIABLES path` | `CustomVariables.ini` to load so global variables get names (`$PLAYER_CHAR` instead of `$2`) |

Checked against the vanilla v1.0 `main.scm` (3,079,599 bytes): the rebuilt file has the same length and an identical header; the
only bytes that differ are the stale bytes after the NUL of 8-byte texts, which the compiler writes as zero. Adding an
instruction to the main thread moves every absolute address, and the missions that start threads there follow it.

Limits: external scripts and `script.img` are not built (only their table entries are kept); the sizes of the header
segments depend on the number of objects, missions and external scripts, which `build` takes from `project.txt`.

# Where to put the files

Everything needed except the game's own `main.scm` is in `res/`:

| File | Used for |
|---|---|
| `res/SASCM.INI` | opcode list (override with `--ini`) |
| `res/vehicles.ide`, `peds.ide`, `default.ide`, `globals.ide` | model names (`#INFERNUS`) and `$PLAYER_CHAR` style names (override with `--ide a.ide,b.ide`) |
| `res/CustomVariables.ini` | names of the vanilla global variables (`2=PLAYER_CHAR`); `export` uses it by default (`--names file` or `--names none`) |
| `res/missions.txt` | names of the 135 missions, written as comments in `project.txt` (`--missions file` or `--missions none`) |
| `res/stripped_main.scm` | a minimal `main.scm` (no missions, one dummy external script) used by the header test |

`CustomVariables.ini`, `missions.txt` and `stripped_main.scm` come from Sanny Builder 4 (`data/sa`, `data/sa_sbl/stripped scm`);
Sanny Builder itself is not needed.

The only file you must provide is **your GTA SA v1.0 `main.scm`** (`<GTA SA>/data/script/main.scm`, 3,079,599 bytes). The repo does not
ship it. Put it in one of these places (first existing one wins):

1. the command line: `java -cp bin Tests path/to/main.scm`
2. `-DGTA_SCM=path/to/main.scm`
3. `res/gta/main.scm` (git-ignored)
4. `D:/Games/GTA San Andreas/data/script/main.scm`

Without it the tests print `SKIP ...` for the main.scm checks and everything else still runs. The tests never modify your files:
the export/build tests work in a temporary folder. `export` and `build` record `VARIABLES res/CustomVariables.ini` in `project.txt`,
so run `build` from the project root (or change that line). To try a rebuilt `main.scm` in the game, back up
`<GTA SA>/data/script/main.scm` first and copy the new one over it.

# Tests

```
java -cp bin Tests [path/to/main.scm]
java -cp bin -DGTA_SCM="E:/GTA SA/data/script/main.scm" Tests
```

Unit tests of the compiler and decompiler, decompile -> compile -> decompile round trips of `res/*.cs`, the header of the
minimal `res/stripped_main.scm`, and, if a GTA SA `main.scm` is available,
the main thread, its 135 missions, and the export/build pipeline including an edit that moves the main thread.
