/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Compiles text in SASCM.ini syntax to CLEO bytecode (.cs).
 *
 * Argument syntax: integers (-5, 0x1F), floats (1.5), 'text8', 'text16', "long text",
 * $VAR / $123 (global), 12@ (local), s$N / v$N / 12@s / 12@v (text variables), #MODEL,
 * @label (jump target), and arrays $VAR(3@,25i) / 8@(2@,10f) (element type i, f, s or v).
 * Jumps are encoded relative to the start of the script (negative value), as CLEO does.
 */
public class ScriptCompiler
{
	private static final Pattern RE_LABEL = Pattern.compile("@[A-Za-z_]\\w*");
	private static final Pattern RE_GLOBAL = Pattern.compile("\\$[A-Za-z_]\\w*|\\$\\d+");
	private static final Pattern RE_MODEL = Pattern.compile("#\\w+");
	private static final Pattern RE_LOCAL = Pattern.compile("(\\d+)@");
	private static final Pattern RE_GSTRVAR = Pattern.compile("s\\$(\\d+)");
	private static final Pattern RE_GSTR16VAR = Pattern.compile("v\\$(\\d+)");
	private static final Pattern RE_LSTRVAR = Pattern.compile("(\\d+)@s");
	private static final Pattern RE_LSTR16VAR = Pattern.compile("(\\d+)@v");
	private static final Pattern RE_ARRAY = Pattern.compile("(\\$[A-Za-z_]\\w*|\\$\\d+|\\d+@)\\((\\$[A-Za-z_]\\w*|\\$\\d+|\\d+@),(\\d+)([ifsv])\\)");
	private static final Pattern RE_FLOAT = Pattern.compile("[+-]?(\\d+\\.\\d+([eE][+-]?\\d+)?|\\d+[eE][+-]?\\d+)f?");
	private static final Pattern RE_INT = Pattern.compile("[+-]?\\d+|[+-]?0[xX][0-9a-fA-F]+");

	private SCMLoader scm;
	private IDECollector ide_collector;
	private HashMap<String, Integer> auto_globals = new HashMap<>();
	private int next_auto_global_index = 25;
	public String error = "";
	public int line_idx = 0;
	/** Output extension given by the {$CLEO .cs} directive. */
	public String format = ".cs";
	/** Absolute jumps (main thread of main.scm): the value is origin + position, positive. Relative and negative by default. */
	public boolean absoluteJumps = false;
	/** Address of the first byte of the script when absoluteJumps is set. */
	public int origin = 0;
	/** Labels defined in another source (e.g. main.txt when compiling a mission): name with '@' -> absolute address, written as a positive value. */
	public Map<String, Integer> externalLabels = new HashMap<>();
	/** Labels of the last compiled source: name with '@' -> address (origin + offset when absoluteJumps, else the offset). */
	public Map<String, Integer> labelAddresses = new HashMap<>();
	/** Local variable slots used by the last compiled source (highest index + 1; text variables count their width). */
	public int localVariableCount;

	private static class Fixup {
		int position;
		String label;
		int line;
	}

	public ScriptCompiler(SCMLoader scm, IDECollector ide) {
		this.scm = scm;
		this.ide_collector = ide;
	}

	public boolean hasError() {
		return error.length() > 0;
	}

	/** Compiles 'path' and writes the binary next to it (extension taken from the directive). Returns a summary or the error. */
	public String compile(String path) {
		return compile(path, null);
	}

	/** Same as compile(path) but writing to 'out' (null = next to the source). */
	public String compile(String path, String out) {
		error = "";
		try {
			String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.ISO_8859_1);
			byte[] code = compileSource(source);
			Files.write(Paths.get(out != null ? out : outputPath(path, format)), code);
			return "Script Length: " + code.length;
		} catch(ScriptFormatException e) {
			error = e.getMessage();
			return error;
		} catch(IOException e) {
			error = "I/O error: " + e.getMessage();
			return error;
		}
	}

	static String outputPath(String path, String format) {
		int dot = path.lastIndexOf('.');
		int sep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		String stem = dot > sep ? path.substring(0, dot) : path;
		return stem + (format.isEmpty() ? ".cs" : format);
	}

	/** Compiles the text of a script and returns the bytecode. Throws ScriptFormatException carrying the line number. */
	public byte[] compileSource(String source) {
		HashMap<String, Integer> labels = new HashMap<>();
		ArrayList<Fixup> fixups = new ArrayList<>();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		FileStreamWriter w = new FileStreamWriter(buffer);
		format = ".cs";
		line_idx = 0;
		localVariableCount = 0;
		for(String raw : source.split("\r?\n", -1)) {
			line_idx++;
			String line = stripComment(raw).trim();
			if(line.isEmpty()) {
				continue;
			}
			if(line.startsWith("{$")) {
				Matcher m = Pattern.compile("\\.cs\\w*").matcher(line);
				if(m.find()) {
					format = m.group();
				}
				continue;
			}
			if(line.startsWith(":")) {
				String name = "@" + line.substring(1).trim();
				if(labels.put(name, buffer.size()) != null) {
					throw fail("duplicate label '" + name + "'");
				}
				continue;
			}
			compileLine(line, w, buffer, fixups);
		}
		byte[] code = buffer.toByteArray();
		labelAddresses.clear();
		for(Map.Entry<String, Integer> e : labels.entrySet()) {
			labelAddresses.put(e.getKey(), absoluteJumps ? origin + e.getValue() : e.getValue());
		}
		for(Fixup f : fixups) {
			Integer address = labels.get(f.label);
			int v;
			if(address != null) {
				v = absoluteJumps ? origin + address : -address;
			} else if(externalLabels.containsKey(f.label)) {
				v = externalLabels.get(f.label);
			} else {
				line_idx = f.line;
				throw fail("undefined label '" + f.label + "'");
			}
			for(int i = 0; i < 4; i++) {
				code[f.position + i] = (byte) (v >> (8 * i));
			}
		}
		return code;
	}

	private ScriptFormatException fail(String message) {
		return new ScriptFormatException("Line " + line_idx + ": " + message, line_idx);
	}

	private void compileLine(String line, FileStreamWriter w, ByteArrayOutputStream buffer, ArrayList<Fixup> fixups) {
		int colon = line.indexOf(':');
		if(colon < 0) {
			throw fail("expected 'XXXX: instruction'");
		}
		String hex = line.substring(0, colon).trim();
		String body = line.substring(colon + 1).trim();
		int id;
		try {
			id = Integer.parseInt(hex, 16);
		} catch(NumberFormatException e) {
			throw fail("'" + hex + "' is not an opcode");
		}
		if(body.startsWith("not ")) {
			id |= 0x8000;
			body = body.substring(4);
		}
		OpcodeInfo info = scm.get(id);
		if(info == null) {
			throw fail("'" + hex + "' Unknown opcode");
		}
		ArrayList<String> tokens = tokenize(body);
		if((id & 0x7FFF) == 0x00D6) {
			if(tokens.isEmpty()) {
				tokens.add("0");
			}
			int n = isInt(tokens.get(0)) ? Integer.decode(tokens.get(0)) : -1;
			if(n < 0 || (n > 7 && n < 21) || n > 27) {
				throw fail("invalid if argument\n1 ... 7 AND operator, 21 ... 27 OR operator, please specify the number of conditions.");
			}
		}
		boolean variadic = info.isVariadic();
		int count = tokens.size();
		if(variadic ? count < info.templateParams() : count != info.param_count) {
			throw fail("missing parameters, expected " + (variadic ? "at least " + info.templateParams() : info.param_count) + ", given " + count);
		}
		// token i -> parameter position according to the order of the %N% placeholders of the template
		String[] params = new String[Math.max(count, info.templateParams())];
		int extra = info.templateParams();
		for(int i = 0; i < count; i++) {
			int pos = i < info.placeholders.length ? info.placeholders[i] : extra++;
			if(pos >= params.length) {
				throw fail("parameter %" + (pos + 1) + "% out of range");
			}
			params[pos] = tokens.get(i);
		}
		w.writeShort(id);
		for(int i = 0; i < params.length; i++) {
			if(params[i] == null) {
				throw fail("missing parameter " + (i + 1));
			}
			writeParam(w, buffer, fixups, id & 0x7FFF, params[i], info.kindOf(i));
		}
		if(variadic) {
			w.writeByte(0); // terminator of the argument list
		}
	}

	private void writeParam(FileStreamWriter w, ByteArrayOutputStream buffer, ArrayList<Fixup> fixups, int op, String arg, char kind) {
		Matcher m;
		if(kind == 'k') {
			// raw 128-byte text, no type byte
			if(arg.length() < 2 || arg.charAt(0) != '"' || arg.length() > 129) {
				throw fail("expected a double-quoted text of up to 127 characters");
			}
			w.writeStringFromSize(128, arg.substring(1, arg.length() - 1));
		} else if(op == 0x00D6) {
			w.writeByte(0x04);
			w.writeByte(Integer.decode(arg));
		} else if(RE_LABEL.matcher(arg).matches()) {
			w.writeByte(0x01);
			Fixup f = new Fixup();
			f.position = buffer.size();
			f.label = arg;
			f.line = line_idx;
			fixups.add(f);
			w.writeInt(0);
		} else if(RE_MODEL.matcher(arg).matches()) {
			int id = ide_collector.getIdByDefinition(arg.substring(1));
			if(id == -1) {
				throw fail("invalid definition '" + arg + "'");
			}
			writeInt(w, id);
		} else if((m = RE_ARRAY.matcher(arg)).matches()) {
			writeArray(w, m);
		} else if((m = RE_GSTRVAR.matcher(arg)).matches()) {
			w.writeByte(0xA);
			w.writeShort(u16(m.group(1)));
		} else if((m = RE_GSTR16VAR.matcher(arg)).matches()) {
			w.writeByte(0x11);
			w.writeShort(u16(m.group(1)));
		} else if((m = RE_LSTRVAR.matcher(arg)).matches()) {
			w.writeByte(0xB);
			w.writeShort(u16(m.group(1)));
			noteLocals(u16(m.group(1)), 2);
		} else if((m = RE_LSTR16VAR.matcher(arg)).matches()) {
			// not counted in the local variable total: the original header ignores them (vanilla mission 22 uses 21244@v)
			w.writeByte(0x10);
			w.writeShort(u16(m.group(1)));
		} else if((m = RE_LOCAL.matcher(arg)).matches()) {
			w.writeByte(0x3);
			w.writeShort(u16(m.group(1)));
			noteLocals(u16(m.group(1)), 1);
		} else if(RE_GLOBAL.matcher(arg).matches()) {
			w.writeByte(0x2);
			w.writeShort(globalOffset(arg));
		} else if(RE_INT.matcher(arg).matches()) {
			writeInt(w, parseInt(arg));
		} else if(RE_FLOAT.matcher(arg).matches()) {
			w.writeByte(0x6);
			w.writeFloat(Float.parseFloat(arg));
		} else if(arg.startsWith("'") && arg.endsWith("'") && arg.length() >= 2) {
			String s = arg.substring(1, arg.length() - 1);
			if(s.length() > 16) {
				throw fail("the text '" + s + "' is longer than 16 characters");
			}
			w.writeByte(s.length() > 8 ? 0xF : 0x9);
			w.writeStringFromSize(s.length() > 8 ? 16 : 8, s);
		} else if(arg.startsWith("\"") && arg.endsWith("\"") && arg.length() >= 2) {
			String s = arg.substring(1, arg.length() - 1);
			if(s.length() > 255) {
				throw fail("the text is longer than 255 characters");
			}
			w.writeByte(0xE);
			w.writeByte(s.length());
			w.writeString(s);
		} else {
			throw fail("unrecognized argument '" + arg + "'");
		}
	}

	/** Integer stored with the smallest type that holds it (int8, int16, int32). */
	private static void writeInt(FileStreamWriter w, int v) {
		if(v >= -128 && v <= 127) {
			w.writeByte(0x4);
			w.writeByte(v);
		} else if(v >= -32768 && v <= 32767) {
			w.writeByte(0x5);
			w.writeShort(v);
		} else {
			w.writeByte(0x1);
			w.writeInt(v);
		}
	}

	private void writeArray(FileStreamWriter w, Matcher m) {
		String base = m.group(1);
		String index = m.group(2);
		int size = Integer.parseInt(m.group(3));
		int elem = "ifsv".indexOf(m.group(4).charAt(0));
		if(size > 255) {
			throw fail("array size " + size + " out of range (max 255)");
		}
		boolean global = base.startsWith("$");
		boolean text = elem >= 2;
		int width = elem == 2 ? 2 : (elem == 3 ? 4 : 1);
		if(!global) {
			noteLocals(u16(base.replace("@", "")), size * width);
		}
		if(!index.startsWith("$")) {
			noteLocals(u16(index.replace("@", "")), 1);
		}
		w.writeByte(global ? (text ? 0xC : 0x7) : (text ? 0xD : 0x8));
		w.writeShort(global ? globalOffset(base) : u16(base.replace("@", "")));
		boolean global_index = index.startsWith("$");
		w.writeShort(global_index ? globalOffset(index) : u16(index.replace("@", "")));
		w.writeByte(size);
		w.writeByte(elem | (global_index ? 0x80 : 0));
	}

	private void noteLocals(int first, int slots) {
		localVariableCount = Math.max(localVariableCount, first + slots);
	}

	private int globalOffset(String arg) {
		int fd = ide_collector.getGlobalIdByName(arg);
		if(fd != -1) {
			return fd;
		}
		if(arg.substring(1).matches("\\d+")) {
			return u16(Long.toString(Long.parseLong(arg.substring(1)) * 4));
		}
		Integer autoIndex = auto_globals.get(arg);
		if(autoIndex == null) {
			autoIndex = next_auto_global_index++;
			auto_globals.put(arg, autoIndex);
		}
		return autoIndex * 4;
	}

	private int u16(String digits) {
		long v;
		try {
			v = Long.parseLong(digits);
		} catch(NumberFormatException e) {
			throw fail("invalid number '" + digits + "'");
		}
		if(v < 0 || v > 0xFFFF) {
			throw fail("value " + v + " out of range (0..65535)");
		}
		return (int) v;
	}

	private int parseInt(String arg) {
		try {
			return Integer.decode(arg.startsWith("+") ? arg.substring(1) : arg);
		} catch(NumberFormatException e) {
			throw fail("integer out of range '" + arg + "'");
		}
	}

	private static boolean isInt(String s) {
		return RE_INT.matcher(s).matches();
	}

	/** Strips a // comment that is outside quotes. */
	static String stripComment(String line) {
		char quote = 0;
		for(int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if(quote != 0) {
				if(c == quote) {
					quote = 0;
				}
			} else if(c == '\'' || c == '"') {
				quote = c;
			} else if(c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
				return line.substring(0, i);
			}
		}
		return line;
	}

	/** Splits the body of the instruction into arguments; template words (which are not arguments) are discarded. */
	private ArrayList<String> tokenize(String body) {
		ArrayList<String> tokens = new ArrayList<>();
		int i = 0, n = body.length();
		while(i < n) {
			char c = body.charAt(i);
			if(Character.isWhitespace(c)) {
				i++;
				continue;
			}
			int j = i;
			if(c == '\'' || c == '"') {
				j = body.indexOf(c, i + 1);
				if(j < 0) {
					throw fail("unterminated quote");
				}
				j++;
			} else {
				while(j < n && !Character.isWhitespace(body.charAt(j))) {
					j++;
				}
			}
			String chunk = body.substring(i, j);
			i = j;
			if(isArgument(chunk)) {
				tokens.add(chunk);
			}
		}
		return tokens;
	}

	private static boolean isArgument(String s) {
		char c = s.charAt(0);
		if(c == '\'' || c == '"') {
			return true;
		}
		return RE_LABEL.matcher(s).matches() || RE_GLOBAL.matcher(s).matches() || RE_MODEL.matcher(s).matches()
			|| RE_LOCAL.matcher(s).matches() || RE_GSTRVAR.matcher(s).matches() || RE_GSTR16VAR.matcher(s).matches()
			|| RE_LSTRVAR.matcher(s).matches() || RE_LSTR16VAR.matcher(s).matches() || RE_ARRAY.matcher(s).matches()
			|| RE_FLOAT.matcher(s).matches() || RE_INT.matcher(s).matches();
	}
}
