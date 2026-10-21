/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Exports a GTA SA main.scm to a folder of editable text and builds it back.
 *
 * Layout of the folder:
 * <pre>
 *   project.txt          header values (Sanny Builder style DEFINE directives)
 *   main.txt             main thread, absolute jumps; labels are named MAIN_&lt;original address&gt;
 *   missions/NNN.txt     one file per mission, relative (negative) jumps; references to the main thread are @MAIN_&lt;address&gt;
 * </pre>
 * build() compiles main.txt first, then every mission resolving @MAIN_x against the labels of main.txt, and finally
 * rebuilds the header: sizes, mission offsets, largest mission, local variable count and external script offsets are recomputed.
 * External scripts (script.img) are not built: only their table entries are kept.
 */
public class ScmProject
{
	public static final String MANIFEST = "project.txt";
	public static final String MAIN_FILE = "main.txt";

	private final SCMLoader scm;
	private final IDECollector ide;

	public ScmProject(SCMLoader scm, IDECollector ide) {
		this.scm = scm;
		this.ide = ide;
	}

	/** Header values read from project.txt. */
	static class Manifest {
		int globalsSize;
		int globalsByte7;
		List<String> objects = new ArrayList<>();
		List<String> missionFiles = new ArrayList<>();
		int exclusiveMissions;
		int largestExternal;
		List<String> externalNames = new ArrayList<>();
		List<Integer> externalSizes = new ArrayList<>();
		List<Integer> externalFixedOffsets = new ArrayList<>(); // -1 = placed after main.scm
		int emptySegment;
		int threadsMemory;
		String mainFile = MAIN_FILE;
		String variables; // CustomVariables.ini to load before compiling (optional)
	}

	/**
	 * Writes the project. missionNames (optional, index -> name) only adds comments.
	 * Fails if any part of the file does not decompile cleanly, because it could not be rebuilt.
	 */
	public void export(ScmFile f, Path dir, List<String> missionNames) throws IOException {
		export(f, dir, missionNames, null);
	}

	/** Same as export(f, dir, missionNames) and records the CustomVariables.ini that was loaded into the IDECollector. */
	public void export(ScmFile f, Path dir, List<String> missionNames, String variablesPath) throws IOException {
		Files.createDirectories(dir.resolve("missions"));
		ScriptDecompiler dec = new ScriptDecompiler(scm, ide);
		// pass 1: missions; collect the main thread addresses they refer to
		TreeSet<Integer> mainRefs = new TreeSet<>();
		String[] texts = new String[f.missionCount()];
		for(int n = 0; n < f.missionCount(); n++) {
			ScriptDecompiler.Options o = new ScriptDecompiler.Options();
			o.start = f.missionStart(n);
			o.end = f.missionEnd(n);
			o.mainStart = f.mainStart;
			o.mainEnd = f.mainSize;
			texts[n] = dec.decompile(f.data, o);
			requireClean(dec, "mission " + n);
			mainRefs.addAll(dec.getMainReferences());
		}
		// pass 2: main thread, forcing a label at every address a mission jumps to
		ScriptDecompiler.Options m = new ScriptDecompiler.Options();
		m.start = f.mainStart;
		m.end = f.mainSize;
		m.base = f.mainStart;
		m.scriptName = "MAIN";
		m.absoluteJumps = true;
		m.forcedLabels = mainRefs;
		String main = dec.decompile(f.data, m);
		requireClean(dec, "main thread");
		write(dir.resolve(MAIN_FILE), main);
		for(int n = 0; n < f.missionCount(); n++) {
			write(dir.resolve(String.format("missions/%03d.txt", n)), texts[n]);
		}
		write(dir.resolve(MANIFEST), manifest(f, missionNames, variablesPath));
	}

	private static void requireClean(ScriptDecompiler dec, String what) {
		if(!dec.getWarnings().isEmpty()) {
			throw new ScriptFormatException(what + " does not decompile cleanly: " + dec.getWarnings().get(0), -1);
		}
	}

	private static void write(Path p, String text) throws IOException {
		Files.write(p, text.getBytes(StandardCharsets.ISO_8859_1));
	}

	private static String manifest(ScmFile f, List<String> missionNames, String variablesPath) {
		StringBuilder sb = new StringBuilder();
		if(variablesPath != null) {
			sb.append("VARIABLES ").append(variablesPath).append('\n');
		}
		sb.append("// main.scm project. Values are rebuilt from the sources where possible; edit with care.\n");
		sb.append("GLOBALS_SIZE ").append(f.globalsSize).append('\n');
		sb.append("GLOBALS_BYTE7 ").append(f.globalsByte7).append('\n');
		sb.append("DEFINE OBJECTS ").append(f.objects.length).append('\n');
		for(String o : f.objects) {
			sb.append("DEFINE OBJECT ").append(o.isEmpty() ? "(noname)" : o).append('\n');
		}
		sb.append("DEFINE MISSIONS ").append(f.missionCount()).append('\n');
		for(int n = 0; n < f.missionCount(); n++) {
			sb.append(String.format("DEFINE MISSION %d FILE missions/%03d.txt", n, n));
			if(missionNames != null && n < missionNames.size()) {
				sb.append(" // ").append(missionNames.get(n));
			}
			sb.append('\n');
		}
		sb.append("EXCLUSIVE_MISSIONS ").append(f.exclusiveMissions).append('\n');
		sb.append("LARGEST_EXTERNAL ").append(f.largestExternal).append('\n');
		sb.append("DEFINE EXTERNAL_SCRIPTS ").append(f.externalNames.length).append('\n');
		for(int i = 0; i < f.externalNames.length; i++) {
			sb.append("DEFINE SCRIPT ").append(f.externalNames[i]).append(' ').append(f.externalSizes[i]);
			if(f.externalOffsets[i] == 0) {
				sb.append(" AT 0"); // placeholder entry (AAA) that is not placed after main.scm
			}
			sb.append('\n');
		}
		sb.append("DEFINE UNKNOWN_EMPTY_SEGMENT ").append(f.emptySegment).append('\n');
		sb.append("DEFINE UNKNOWN_THREADS_MEMORY ").append(f.threadsMemory).append('\n');
		sb.append("MAIN_FILE ").append(MAIN_FILE).append('\n');
		return sb.toString();
	}

	static Manifest readManifest(Path file) throws IOException {
		Manifest m = new Manifest();
		int lineNo = 0;
		for(String raw : new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\r?\n")) {
			lineNo++;
			String line = raw;
			int c = line.indexOf("//");
			if(c >= 0) {
				line = line.substring(0, c);
			}
			line = line.trim();
			if(line.isEmpty()) {
				continue;
			}
			String[] w = line.split("\\s+");
			try {
				switch(w[0]) {
					case "GLOBALS_SIZE": m.globalsSize = Integer.decode(w[1]); break;
					case "GLOBALS_BYTE7": m.globalsByte7 = Integer.decode(w[1]); break;
					case "EXCLUSIVE_MISSIONS": m.exclusiveMissions = Integer.decode(w[1]); break;
					case "LARGEST_EXTERNAL": m.largestExternal = Integer.decode(w[1]); break;
					case "MAIN_FILE": m.mainFile = w[1]; break;
					case "VARIABLES": m.variables = line.substring("VARIABLES".length()).trim(); break;
					case "DEFINE":
						define(m, w, line);
						break;
					default:
						throw new IllegalArgumentException("unknown directive");
				}
			} catch(RuntimeException e) {
				throw new ScriptFormatException(file.getFileName() + ":" + lineNo + ": " + e.getMessage() + " in '" + raw.trim() + "'", lineNo);
			}
		}
		return m;
	}

	private static void define(Manifest m, String[] w, String line) {
		switch(w[1]) {
			case "OBJECTS":
			case "MISSIONS":
			case "EXTERNAL_SCRIPTS":
				break; // counts are implied by the entries that follow
			case "OBJECT": {
				String name = line.substring(line.indexOf("OBJECT") + 6).trim();
				m.objects.add(name.equals("(noname)") ? "" : name);
				break;
			}
			case "MISSION": {
				int n = Integer.parseInt(w[2]);
				if(n != m.missionFiles.size() || !w[3].equals("FILE")) {
					throw new IllegalArgumentException("missions must be listed in order as 'DEFINE MISSION n FILE path'");
				}
				m.missionFiles.add(w[4]);
				break;
			}
			case "SCRIPT":
				m.externalNames.add(w[2]);
				m.externalSizes.add(Integer.decode(w[3]));
				m.externalFixedOffsets.add(w.length >= 6 && w[4].equals("AT") ? Integer.decode(w[5]) : -1);
				break;
			case "UNKNOWN_EMPTY_SEGMENT": m.emptySegment = Integer.decode(w[2]); break;
			case "UNKNOWN_THREADS_MEMORY": m.threadsMemory = Integer.decode(w[2]); break;
			default:
				throw new IllegalArgumentException("unknown DEFINE");
		}
	}

	/** Compiles the project in dir and returns the bytes of main.scm. */
	public byte[] build(Path dir) throws IOException {
		Manifest m = readManifest(dir.resolve(MANIFEST));
		if(m.variables != null) {
			ide.loadVariables(m.variables);
		}
		int nObj = m.objects.size(), nMis = m.missionFiles.size(), nExt = m.externalNames.size();
		// header layout: globals | objects | missions | externals | empty segment | variables/threads
		int objStart = m.globalsSize;
		int misStart = objStart + 12 + nObj * ScmFile.NAME_SIZE;
		int extStart = misStart + 24 + nMis * 4;
		int emptyStart = extStart + 16 + nExt * ScmFile.EXTERNAL_ENTRY_SIZE;
		int varsStart = emptyStart + 12;
		int mainStart = varsStart + 16;

		ScriptCompiler mainCompiler = new ScriptCompiler(scm, ide);
		mainCompiler.absoluteJumps = true;
		mainCompiler.origin = mainStart;
		byte[] main = compileFile(mainCompiler, dir.resolve(m.mainFile));
		Map<String, Integer> mainLabels = new HashMap<>(mainCompiler.labelAddresses);

		byte[][] missions = new byte[nMis][];
		int largest = 0, locals = 0;
		for(int n = 0; n < nMis; n++) {
			ScriptCompiler c = new ScriptCompiler(scm, ide);
			c.externalLabels = mainLabels;
			missions[n] = compileFile(c, dir.resolve(m.missionFiles.get(n)));
			largest = Math.max(largest, missions[n].length);
			locals = Math.max(locals, c.localVariableCount);
		}

		int mainEnd = mainStart + main.length;
		int total = mainEnd;
		int[] missionOffsets = new int[nMis];
		for(int n = 0; n < nMis; n++) {
			missionOffsets[n] = total;
			total += missions[n].length;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FileStreamWriter w = new FileStreamWriter(out);
		// segment 0: global variables (all zero but byte 7)
		gotoNext(w, objStart);
		byte[] globals = new byte[objStart - 7];
		globals[0] = (byte) m.globalsByte7;
		w.writeByteArray(globals);
		// segment id 0: objects
		gotoNext(w, misStart);
		w.writeByte(0);
		w.writeInt(nObj);
		for(String o : m.objects) {
			w.writeStringFromSize(ScmFile.NAME_SIZE, o);
		}
		// segment id 1: missions
		gotoNext(w, extStart);
		w.writeByte(1);
		w.writeInt(mainEnd);
		w.writeInt(largest);
		w.writeShort(nMis);
		w.writeShort(m.exclusiveMissions);
		w.writeInt(locals);
		for(int off : missionOffsets) {
			w.writeInt(off);
		}
		// segment id 2: external scripts, placed one after another after the end of main.scm
		gotoNext(w, emptyStart);
		w.writeByte(2);
		w.writeInt(m.largestExternal);
		w.writeInt(nExt);
		int cum = 0;
		for(int i = 0; i < nExt; i++) {
			w.writeStringFromSize(ScmFile.EXTERNAL_NAME_SIZE, m.externalNames.get(i));
			int fixed = m.externalFixedOffsets.get(i);
			if(fixed >= 0) {
				w.writeInt(fixed);
			} else {
				w.writeInt(total + cum);
				cum += m.externalSizes.get(i);
			}
			w.writeInt(m.externalSizes.get(i));
		}
		// segment id 3: empty
		gotoNext(w, varsStart);
		w.writeByte(3);
		w.writeInt(m.emptySegment);
		// segment id 4: variables space and threads memory; the main thread follows
		gotoNext(w, mainStart);
		w.writeByte(4);
		w.writeInt(m.globalsSize - 8);
		w.writeInt(m.threadsMemory);
		w.writeByteArray(main);
		for(byte[] b : missions) {
			w.writeByteArray(b);
		}
		return out.toByteArray();
	}

	private static void gotoNext(FileStreamWriter w, int target) {
		w.writeShort(0x0002);
		w.writeByte(1);
		w.writeInt(target);
	}

	private static byte[] compileFile(ScriptCompiler c, Path file) throws IOException {
		try {
			return c.compileSource(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
		} catch(ScriptFormatException e) {
			throw new ScriptFormatException(file.getFileName() + ": " + e.getMessage(), e.position);
		}
	}

	/** Reads one mission name per line (data/sa/missions.txt of Sanny Builder). */
	public static List<String> readMissionNames(Path file) throws IOException {
		List<String> names = new ArrayList<>();
		for(String line : new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\r?\n")) {
			if(!line.trim().isEmpty()) {
				names.add(line.trim());
			}
		}
		return names;
	}
}
