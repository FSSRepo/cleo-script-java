/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import cleo.*;

public class Main
{
	private static final String USAGE =
		"Usage:\n" +
		"  java -cp bin Main                        demo: compiles res/bigfoot.txt and decompiles res/cheats.cs\n" +
		"  java -cp bin Main decompile <file> [options]    decompiles a .cs / .scm / binary file\n" +
		"  java -cp bin Main compile <source.txt> [-o output] [--absolute --base N] [--footer]\n" +
		"                                                      compiles to .cs (--footer appends a Sanny Builder footer with the variable names)\n" +
		"  java -cp bin Main export <main.scm> <dir> [--missions file|none] [--names file|none]   export main.scm to editable text (default res/missions.txt, res/CustomVariables.ini)\n" +
		"  java -cp bin Main build <dir> -o <main.scm>         rebuild main.scm (header, offsets and references recomputed)\n" +
		"  java -cp bin Main scm <main.scm> info               segments and mission table\n" +
		"  java -cp bin Main scm <main.scm> main [options]    main thread (absolute jumps)\n" +
		"  java -cp bin Main scm <main.scm> mission <n> [options]   mission n (relative jumps)\n" +
		"Decompiler options:\n" +
		"  -o <file>        output file (default: stdout)\n" +
		"  --start N --end N   byte range (0x.. accepted)\n" +
		"  --base N            address of the first byte of the range\n" +
		"  --name NAME         name used for labels\n" +
		"  --absolute          positive jump values are absolute addresses (main thread of main.scm)\n" +
		"  --no-footer         ignore the Sanny Builder footer of a .cs (by default the code stops there and its variable names are used)\n" +
		"  --offsets           appends '// address' to every line\n" +
		"Common options:\n" +
		"  --ini <a.ini,b.ini> opcode lists, later ones override (default res/SASCM.INI)\n" +
		"  --ide <a.ide,b.ide,...>   (default res/vehicles.ide,peds.ide,default.ide,globals.ide)\n";

	public static void main(String[] args)
	{
		try {
			if(args.length == 0) {
				demo();
				return;
			}
			run(args);
		} catch(IOException | ScriptFormatException | UncheckedIOException | IllegalArgumentException e) {
			System.err.println("Error: " + e.getMessage());
			System.exit(1);
		}
	}

	private static void run(String[] args) throws IOException {
		String cmd = args[0];
		if(cmd.equals("-h") || cmd.equals("--help") || cmd.equals("help")) {
			System.out.print(USAGE);
			return;
		}
		Map<String, String> opt = new HashMap<>();
		ArrayList<String> pos = new ArrayList<>();
		for(int i = 1; i < args.length; i++) {
			String a = args[i];
			if(a.equals("--offsets") || a.equals("--absolute") || a.equals("--footer") || a.equals("--no-footer")) {
				opt.put(a.substring(2), "1");
			} else if(a.startsWith("-") && a.length() > 1 && !Character.isDigit(a.charAt(1))) {
				if(i + 1 >= args.length) {
					throw new IllegalArgumentException("missing value for " + a);
				}
				opt.put(a.replaceFirst("^-+", ""), args[++i]);
			} else {
				pos.add(a);
			}
		}
		IDECollector ide = new IDECollector();
		String ides = opt.getOrDefault("ide", "res/vehicles.ide,res/peds.ide,res/default.ide,res/globals.ide");
		for(String f : ides.split(",")) {
			ide.load(f.trim());
		}
		SCMLoader scm = new SCMLoader();
		// several opcode lists can be given (later files override earlier ones), e.g. Sanny Builder's SASCM.INI,SASCM.CLEO.ini
		for(String f : opt.getOrDefault("ini", "res/SASCM.INI").split(",")) {
			scm.load(f.trim());
		}
		switch(cmd) {
			case "compile": {
				need(pos, 1, "compile <source.txt>");
				ScriptCompiler comp = new ScriptCompiler(scm, ide);
				comp.absoluteJumps = opt.containsKey("absolute");
				comp.footer = opt.containsKey("footer");
				if(opt.containsKey("base")) {
					comp.origin = Integer.decode(opt.get("base"));
				}
				String r = comp.compile(pos.get(0), opt.get("o"));
				System.out.println(r);
				if(comp.hasError()) {
					System.exit(1);
				}
				break;
			}
			case "decompile": {
				need(pos, 1, "decompile <file>");
				decompile(scm, ide, read(pos.get(0)), options(opt), opt.get("o"));
				break;
			}
			case "export": {
				need(pos, 2, "export <main.scm> <dir>");
				ScmProject project = new ScmProject(scm, ide);
				// res/CustomVariables.ini and res/missions.txt are used by default; "--names none" / "--missions none" disable them
				String variables = optionalFile(opt, "names", "res/CustomVariables.ini");
				String missionsFile = optionalFile(opt, "missions", "res/missions.txt");
				if(variables != null) {
					ide.loadVariables(variables);
				}
				List<String> names = missionsFile != null ? ScmProject.readMissionNames(Paths.get(missionsFile)) : null;
				project.export(ScmFile.load(pos.get(0)), Paths.get(pos.get(1)), names, variables);
				System.out.println("exported to " + pos.get(1));
				break;
			}
			case "build": {
				need(pos, 1, "build <dir> -o <main.scm>");
				byte[] built = new ScmProject(scm, ide).build(Paths.get(pos.get(0)));
				Files.write(Paths.get(opt.getOrDefault("o", "main.scm")), built);
				System.out.println("main.scm: " + built.length + " bytes");
				break;
			}
			case "scm": {
				need(pos, 2, "scm <main.scm> info|main|mission <n>");
				ScmFile f = ScmFile.load(pos.get(0));
				ScriptDecompiler.Options o = options(opt);
				switch(pos.get(1)) {
					case "info":
						info(f);
						break;
					case "main":
						if(!opt.containsKey("start")) {
							o.start = f.mainStart;
						}
						if(!opt.containsKey("end")) {
							o.end = f.mainSize;
						}
						if(!opt.containsKey("base")) {
							o.base = o.start;
						}
						if(o.scriptName == null) {
							o.scriptName = "MAIN";
						}
						o.absoluteJumps = true;
						decompile(scm, ide, f.data, o, opt.get("o"));
						break;
					case "mission": {
						need(pos, 3, "scm <main.scm> mission <n>");
						int n = Integer.parseInt(pos.get(2));
						if(!opt.containsKey("start")) {
							o.start = f.missionStart(n);
						}
						if(!opt.containsKey("end")) {
							o.end = f.missionEnd(n);
						}
						decompile(scm, ide, f.data, o, opt.get("o"));
						break;
					}
					default:
						throw new IllegalArgumentException("unknown scm subcommand: " + pos.get(1));
				}
				break;
			}
			default:
				System.err.print(USAGE);
				throw new IllegalArgumentException("unknown command: " + cmd);
		}
	}

	/** Value of an option naming an optional file: the given path, "none" for no file, or the default if it exists. */
	private static String optionalFile(Map<String, String> opt, String name, String defaultPath) {
		String v = opt.get(name);
		if(v != null) {
			return v.equals("none") ? null : v;
		}
		return Files.exists(Paths.get(defaultPath)) ? defaultPath : null;
	}

	private static void need(List<String> pos, int n, String what) {
		if(pos.size() < n) {
			throw new IllegalArgumentException("usage: " + what);
		}
	}

	private static ScriptDecompiler.Options options(Map<String, String> opt) {
		ScriptDecompiler.Options o = new ScriptDecompiler.Options();
		if(opt.containsKey("start")) o.start = Integer.decode(opt.get("start"));
		if(opt.containsKey("end")) o.end = Integer.decode(opt.get("end"));
		if(opt.containsKey("base")) o.base = Integer.decode(opt.get("base"));
		if(opt.containsKey("name")) o.scriptName = opt.get("name");
		o.showOffsets = opt.containsKey("offsets");
		o.absoluteJumps = opt.containsKey("absolute");
		o.useFooter = !opt.containsKey("no-footer");
		return o;
	}

	private static byte[] read(String path) {
		try {
			return Files.readAllBytes(Paths.get(path));
		} catch(IOException e) {
			throw new UncheckedIOException("Cannot read " + path, e);
		}
	}

	private static void decompile(SCMLoader scm, IDECollector ide, byte[] data, ScriptDecompiler.Options o, String out) {
		ScriptDecompiler deco = new ScriptDecompiler(scm, ide);
		String text = deco.decompile(data, o);
		try {
			if(out != null) {
				Files.write(Paths.get(out), text.getBytes(StandardCharsets.ISO_8859_1));
			} else {
				System.out.print(text);
			}
		} catch(IOException e) {
			throw new UncheckedIOException("Cannot write " + out, e);
		}
		for(String w : deco.getWarnings()) {
			System.err.println("warning: " + w);
		}
	}

	private static void info(ScmFile f) {
		System.out.printf("size: %d bytes%n", f.data.length);
		for(int i = 0; i < f.segmentStart.length; i++) {
			System.out.printf("segment %d at 0x%X (id %d)%n", i, f.segmentStart[i], f.segmentId[i]);
		}
		System.out.printf("main thread: 0x%X .. 0x%X (%d bytes)%n", f.mainStart, f.mainSize, f.mainSize - f.mainStart);
		System.out.printf("missions: %d (exclusive %d), largest %d bytes, local variables %d%n",
			f.missionCount(), f.exclusiveMissions, f.largestMission, f.localVars);
		for(int i = 0; i < f.missionCount(); i++) {
			System.out.printf("  mission %3d: 0x%X .. 0x%X (%d bytes)%n", i, f.missionStart(i), f.missionEnd(i), f.missionEnd(i) - f.missionStart(i));
		}
	}

	/** Original demo: compiles res/bigfoot.txt and decompiles res/cheats.cs. */
	private static void demo() {
		// Loads constants and global variables to better code format
		IDECollector ide_col = new IDECollector();
		ide_col.load("res/vehicles.ide");
		ide_col.load("res/peds.ide");
		ide_col.load("res/default.ide");
		ide_col.load("res/globals.ide");

		// load SASCM opcode list
		SCMLoader scm = new SCMLoader();
		scm.load("res/SASCM.INI");

		// compile a script
		ScriptCompiler dec = new ScriptCompiler(scm, ide_col);
		System.out.println(dec.compile("res/bigfoot.txt"));

		// decompile a script
		ScriptDecompiler deco = new ScriptDecompiler(scm, ide_col);
		deco.decompile("res/cheats.cs", true);
		for(String w : deco.getWarnings()) {
			System.err.println("warning: " + w);
		}
	}
}
