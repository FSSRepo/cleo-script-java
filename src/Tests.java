/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import cleo.*;

/**
 * Dependency-free tests: java -cp bin Tests [main.scm]
 * (run from the project root: it uses res/). With a GTA SA main.scm it also checks the main thread,
 * the 135 missions and the export/build pipeline; without an argument it looks in the usual GTA SA location.
 * The GTA SA main.scm is looked up in this order: command line, -DGTA_SCM, res/gta/main.scm, and the default
 * install path (see the README). Everything else the tests need is in res/.
 */
public class Tests
{
	private static int passed = 0, failed = 0;
	private static SCMLoader scm;
	private static IDECollector ide;

	public static void main(String[] args) throws Exception
	{
		ide = new IDECollector();
		for(String f : new String[] { "vehicles", "peds", "default", "globals" }) {
			ide.load("res/" + f + ".ide");
		}
		scm = new SCMLoader();
		scm.load("res/SASCM.INI");

		compilerBasics();
		compilerErrors();
		decompilerBasics();
		roundTripCleo();
		minimalHeader("res/stripped_main.scm");
		String scmPath = locate(args.length > 0 ? args[0] : null, "GTA_SCM", null, "res/gta/main.scm", "D:/Games/GTA San Andreas/data/script/main.scm");
		if(scmPath != null) {
			mainScm(scmPath);
			project(scmPath);
		} else {
			System.out.println("SKIP main.scm: not found (see 'Where to put the files' in the README)");
		}
		System.out.println(passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}

	/** First existing path among: explicit argument, system property, environment variable, repo-relative path, default path. */
	private static String locate(String argument, String property, String environment, String repoRelative, String fallback) {
		String[] candidates = { argument, System.getProperty(property), environment == null ? null : System.getenv(environment), repoRelative, fallback };
		for(String c : candidates) {
			if(c != null && Files.exists(Paths.get(c))) {
				return c;
			}
		}
		return null;
	}

	private static void check(String name, boolean ok) {
		if(ok) {
			passed++;
		} else {
			failed++;
			System.out.println("FAIL: " + name);
		}
	}

	private static void eq(String name, Object expected, Object actual) {
		boolean ok = Objects.equals(expected, actual);
		if(!ok) {
			System.out.println("  expected: " + expected + "\n  actual:   " + actual);
		}
		check(name, ok);
	}

	private static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for(byte x : b) {
			sb.append(String.format("%02x", x));
		}
		return sb.toString();
	}

	private static byte[] compile(String src) {
		return new ScriptCompiler(scm, ide).compileSource(src);
	}

	private static String hexOf(String src) {
		return hex(compile(src));
	}

	private static String failure(String src) {
		try {
			compile(src);
			return null;
		} catch(ScriptFormatException e) {
			return e.getMessage();
		}
	}

	private static void compilerBasics() {
		// integers use the smallest type: int8, int16, int32
		eq("int8", "01000400", hexOf("0001: wait 0 ms"));
		eq("int16", "010005e803", hexOf("0001: wait 1000 ms"));
		eq("int32", "01000150c30000", hexOf("0001: wait 50000 ms"));
		eq("negative", "010004ff", hexOf("0001: wait -1 ms"));
		// $N is encoded as N*4 (a new variable used to be allocated)
		eq("numeric global", "04000204000400", hexOf("0004: $1 = 0"));
		eq("3-digit local", "06000364000405", hexOf("0006: 100@ = 5"));
		eq("float", "0700030100060000c03f", hexOf("0007: 1@ = 1.5"));
		eq("text8", "a40309534352495054" + "0000", hexOf("03A4: script_name 'SCRIPT'"));
		check("text16 uses type F", hexOf("03A4: script_name 'ABCDEFGHIJ'").startsWith("a4030f"));
		// jumps relative to the start (negative); a label at 0 stays 0
		eq("jump to 0", "02000100000000", hexOf(":A\n0002: jump @A"));
		eq("forward jump", "020001f9ffffff01000400", hexOf("0002: jump @B\n:B\n0001: wait 0 ms"));
		// variadic opcodes end with 0x00
		eq("variadic", "4f0001f8ffffff00", hexOf("004F: start_new_script @L\n:L"));
		eq("variadic with arguments", "4f0001f6ffffff040500", hexOf("004F: start_new_script @L 5\n:L"));
		// arrays: $5(3@,25i) = global 5, local index 3, 25 elements, int type
		eq("array", "0400071400030019000401", hexOf("0004: $5(3@,25i) = 1"));
		eq("array with global index (flag 0x80)", "0400071400080019800401", hexOf("0004: $5($2,25i) = 1"));
		// raw 128-byte text without a type byte (05B6)
		eq("05B6 has no type byte, 128 bytes", 2 + 128, compile("05B6: save_string_to_debug_file \"HOLA\"").length);
		eq("// comment outside quotes", hexOf("0001: wait 0 ms"), hexOf("0001: wait 0 ms // hello"));
		check("not sets the bit", hexOf("8039: not 1@ == 0").startsWith("3980"));
		// local variable accounting used for the mission table
		ScriptCompiler c = new ScriptCompiler(scm, ide);
		c.compileSource("0006: 5@ = 1\n05AA: 10@s = 'HI'");
		eq("local variable count", 12, c.localVariableCount);
	}

	private static void compilerErrors() {
		String e = failure("0001: wait 0 ms\nFFFF: nothing");
		check("unknown opcode with line", e != null && e.startsWith("Line 2:") && e.contains("Unknown opcode"));
		e = failure("0001: wait");
		check("missing parameters", e != null && e.startsWith("Line 1:") && e.contains("missing parameters"));
		e = failure("0002: jump @MISSING");
		check("undefined label", e != null && e.contains("undefined label"));
		e = failure(":A\n0001: wait 0 ms\n:A");
		check("duplicate label", e != null && e.startsWith("Line 3:"));
		e = failure("0006: 70000@ = 1");
		check("local out of range", e != null && e.contains("out of range"));
		e = failure("03A4: script_name 'WAY_TOO_LONG_FOR_16_CHARS'");
		check("text longer than 16", e != null && e.contains("16 characters"));
		e = failure("0004: $1 = #NOSUCHMODEL");
		check("unknown model", e != null && e.contains("invalid definition"));
		// labels from another source (main.txt) are written as positive absolute addresses
		ScriptCompiler c = new ScriptCompiler(scm, ide);
		c.externalLabels = new HashMap<>(Collections.singletonMap("@MAIN_5", 1000));
		eq("external label", "4f0001e8030000" + "00", hex(c.compileSource("004F: start_new_script @MAIN_5")));
	}

	private static String decompile(byte[] code) {
		ScriptDecompiler.Options o = new ScriptDecompiler.Options();
		o.header = false;
		return new ScriptDecompiler(scm, ide).decompile(code, o);
	}

	private static void decompilerBasics() {
		eq("wait", "0001: wait 1000 ms\n", decompile(compile("0001: wait 1000 ms")));
		eq("float without exponent", "0007: 1@ = 11500000000.0\n", decompile(compile("0007: 1@ = 11500000000.0")));
		check("variadic with label", decompile(compile(":L\n004F: start_new_script @L 5")).contains("004F: start_new_script @SCRIPT_0 5"));
		check("array", decompile(compile("0004: $5(3@,25i) = 1")).contains("$5(3@,25i)"));
		check("text variable", decompile(compile("05A9: s$10 = 'HOLA'")).contains("s$10"));
		// unknown opcode: stops with a warning instead of emitting garbage
		ScriptDecompiler d = new ScriptDecompiler(scm, ide);
		ScriptDecompiler.Options o = new ScriptDecompiler.Options();
		o.header = false;
		eq("unknown opcode keeps what was decoded", "0001: wait 0 ms\n",
			d.decompile(new byte[] { 0x01, 0x00, 0x04, 0x00, (byte) 0xFE, 0x7F }, o));
		check("unknown opcode: warning", d.getWarnings().size() == 1);
		d.decompile(new byte[] { 0x01, 0x00, 0x01, 0x00 }, o);
		check("truncated data: warning", d.getWarnings().size() == 1);
		// a "blocked" script: it starts with a jump over junk bytes
		byte[] blockedScript = { 0x02, 0x00, 0x01, (byte) 0xE9, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xEE, (byte) 0xEE,
			(byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE,
			(byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE, 0x01, 0x00, 0x04, 0x05 };
		ScriptDecompiler.Options bo = new ScriptDecompiler.Options();
		bo.header = false;
		String unblocked = new ScriptDecompiler(scm, ide).decompile(blockedScript, bo);
		check("blocked script: continues after the jump", unblocked.contains("0001: wait 5 ms") && unblocked.contains("CLEO Script blocked"));
		bo.unblock = false;
		ScriptDecompiler plain = new ScriptDecompiler(scm, ide);
		plain.decompile(blockedScript, bo);
		check("blocked script: unblock=false stops with a warning", plain.getWarnings().size() == 1);
		// references to the main thread become @MAIN_x labels
		ScriptDecompiler.Options r = new ScriptDecompiler.Options();
		r.header = false;
		r.mainStart = 1000;
		r.mainEnd = 2000;
		String text = new ScriptDecompiler(scm, ide).decompile(compile("004F: start_new_script 1500"), r);
		check("main reference", text.contains("@MAIN_1500"));
	}

	private static void roundTripCleo() throws IOException {
		// decompile -> compile -> decompile gives the same text (with the footer, so the variable names survive)
		for(String f : new String[] { "res/cheats.cs", "res/bigfoot.cs" }) {
			byte[] bin = Files.readAllBytes(Paths.get(f));
			ScriptDecompiler dec = new ScriptDecompiler(scm, ide);
			String t1 = dec.decompile(bin, new ScriptDecompiler.Options());
			check("no warnings for " + f + " " + dec.getWarnings(), dec.getWarnings().isEmpty());
			ScriptCompiler c = new ScriptCompiler(scm, ide);
			c.footer = true;
			eq("round trip " + f, t1, dec.decompile(c.compileSource(t1), new ScriptDecompiler.Options()));
		}
		// cheats.cs was compiled by Sanny Builder: it carries a footer with 754 variable names
		byte[] cheats = Files.readAllBytes(Paths.get("res/cheats.cs"));
		ScriptFooter footer = ScriptFooter.parse(cheats);
		check("cheats.cs has a Sanny footer", footer != null);
		if(footer != null) {
			eq("footer: code length", 51488, footer.codeLength);
			eq("footer: variable count", 754, footer.variables.size());
			eq("footer: Active_Interior is $43", Integer.valueOf(43), footer.variables.get("Active_Interior"));
			check("footer: extended", footer.extended);
			// our compiler reproduces Sanny's code; the only differences are the stale bytes after the NUL of 8-byte texts
			ScriptDecompiler dec = new ScriptDecompiler(scm, ide);
			byte[] ours = compile(dec.decompile(cheats, new ScriptDecompiler.Options()));
			eq("cheats.cs: same code size as Sanny's", footer.codeLength, ours.length);
			boolean onlyPadding = true;
			for(int i = 0; i < ours.length; i++) {
				onlyPadding &= cheats[i] == ours[i] || ours[i] == 0;
			}
			check("cheats.cs: bytes that differ are only text padding", onlyPadding);
		}
		check("no footer in a script without it", ScriptFooter.parse(Files.readAllBytes(Paths.get("res/bigfoot.cs"))) == null);
		byte[] a = Files.readAllBytes(Paths.get("res/bigfoot.cs"));
		String t = new ScriptDecompiler(scm, ide).decompile(a, new ScriptDecompiler.Options());
		check("bigfoot: identical bytes", Arrays.equals(a, compile(t)));
	}

	/** Header of a minimal main.scm (res/stripped_main.scm: no missions, one dummy external script, built by Sanny Builder). */
	private static void minimalHeader(String file) {
		ScmFile f = ScmFile.load(file);
		eq("Minimal main.scm: globals size", 0x678, f.globalsSize);
		eq("Minimal main.scm: variables space = globals size - 8", f.globalsSize - 8, f.variablesSpace);
		eq("Minimal main.scm: no threads memory", 0, f.threadsMemory);
		eq("Minimal main.scm: main size is the file size", f.data.length, f.mainSize);
		eq("Minimal main.scm: no missions", 0, f.missionCount());
		eq("Minimal main.scm: one dummy external", "AAA", f.externalNames.length == 1 ? f.externalNames[0] : "?");
	}

	private static void mainScm(String path) {
		ScmFile f = ScmFile.load(path);
		check("main.scm: 135 missions", f.missionCount() == 135);
		eq("main.scm: variables space = globals size - 8", f.globalsSize - 8, f.variablesSpace);
		ScriptDecompiler dec = new ScriptDecompiler(scm, ide);
		ScriptDecompiler.Options main = new ScriptDecompiler.Options();
		main.start = f.mainStart;
		main.end = f.mainSize;
		main.base = f.mainStart;
		main.absoluteJumps = true;
		dec.decompile(f.data, main);
		check("main thread without warnings " + dec.getWarnings(), dec.getWarnings().isEmpty());
		int bad = 0, differ = 0;
		for(int n = 0; n < f.missionCount(); n++) {
			ScriptDecompiler.Options o = new ScriptDecompiler.Options();
			o.start = f.missionStart(n);
			o.end = f.missionEnd(n);
			String t1 = dec.decompile(f.data, o);
			if(!dec.getWarnings().isEmpty()) {
				bad++;
				System.out.println("mission " + n + ": " + dec.getWarnings());
				continue;
			}
			byte[] bin = compile(t1);
			if(bin.length != o.end - o.start) {
				differ++;
				System.out.println("mission " + n + ": size " + bin.length + " != " + (o.end - o.start));
			}
			if(!t1.equals(dec.decompile(bin, new ScriptDecompiler.Options()))) {
				differ++;
				System.out.println("mission " + n + ": the text changes after recompiling");
			}
		}
		check("missions decompile without warnings", bad == 0);
		check("missions recompile with the same size and text", differ == 0);
		// the cutscene of mission 2 (INTRO)
		ScriptDecompiler.Options o = new ScriptDecompiler.Options();
		o.start = f.missionStart(2);
		o.end = f.missionEnd(2);
		o.showOffsets = true;
		String intro = dec.decompile(f.data, o);
		check("INTRO: load_cutscene_data 'PROLOG1' at 284", intro.contains("02E4: load_cutscene_data 'PROLOG1' // 284"));
		check("INTRO: start_cutscene at 404", intro.contains("02E7: start_cutscene // 404"));
	}

	/** export -> build of the real main.scm, then an edit that moves the main thread. */
	private static void project(String path) throws IOException {
		Path dir = Files.createTempDirectory("scmproject");
		byte[] original = Files.readAllBytes(Paths.get(path));
		ScmProject project = new ScmProject(scm, ide);
		project.export(new ScmFile(original), dir, null);
		byte[] rebuilt = project.build(dir);
		eq("build: same length", original.length, rebuilt.length);
		ScmFile of = new ScmFile(original);
		eq("build: identical header", hex(Arrays.copyOf(original, of.mainStart)), hex(Arrays.copyOf(rebuilt, of.mainStart)));
		int differing = 0;
		boolean onlyPadding = true;
		for(int i = 0; i < original.length; i++) {
			if(original[i] != rebuilt[i]) {
				differing++;
				onlyPadding &= rebuilt[i] == 0; // stale bytes after the NUL of 8-byte texts
			}
		}
		check("build: bytes that differ are only text padding (" + differing + ")", onlyPadding);

		// insert 'wait 0' (4 bytes) right after script_name 'MAIN': every absolute address after it moves by 4
		Path mainTxt = dir.resolve(ScmProject.MAIN_FILE);
		String text = new String(Files.readAllBytes(mainTxt), StandardCharsets.ISO_8859_1);
		int at = text.indexOf('\n', text.indexOf("script_name 'MAIN'")) + 1;
		Files.write(mainTxt, (text.substring(0, at) + "0001: wait 0 ms\n" + text.substring(at)).getBytes(StandardCharsets.ISO_8859_1));
		byte[] edited = project.build(dir);
		ScmFile ef = new ScmFile(edited);
		eq("edit: main grows by 4", of.mainSize + 4, ef.mainSize);
		eq("edit: file grows by 4", original.length + 4, edited.length);
		eq("edit: locals unchanged", of.localVars, ef.localVars);
		// mission 2 (INTRO) starts the thread that was at 158297: it must now be at 158301 and still be 'A_CONT'
		ScriptDecompiler dec = new ScriptDecompiler(scm, ide);
		ScriptDecompiler.Options o = new ScriptDecompiler.Options();
		o.start = ef.missionStart(2);
		o.end = ef.missionEnd(2);
		check("edit: mission 2 points to the moved thread", dec.decompile(edited, o).contains("start_new_script 158301"));
		byte[] sig = "A_CONT".getBytes(StandardCharsets.ISO_8859_1);
		boolean found = true;
		for(int i = 0; i < sig.length; i++) {
			found &= edited[158301 + 3 + i] == sig[i]; // 03A4 + type 09 + name
		}
		check("edit: the moved thread is still A_CONT", found);
	}
}
