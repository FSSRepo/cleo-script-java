/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Decompiles SCM/CLEO bytecode to text (SASCM.ini syntax).
 *
 * Handles whole CLEO scripts (.cs) and ranges of main.scm / missions: see {@link Options}.
 * Argument types (the byte that precedes every parameter):
 * 0 end (variadic), 1 int32, 2 global var, 3 local var, 4 int8, 5 int16, 6 float32,
 * 7/8 global/local array, 9 text8, A/B global/local text8 var, C/D global/local text array,
 * E length-prefixed text, F text16, 10/11 local/global text16 var.
 */
public class ScriptDecompiler
{
	/** Decompiler options. */
	public static class Options {
		/** First byte to decode. */
		public int start = 0;
		/** End (exclusive); -1 = up to the end of the data. */
		public int end = -1;
		/** Address assigned to the byte at 'start' (for labels and jumps). */
		public int base = 0;
		/** Script name used for labels; null = the one of the first script_name (03A4) or "SCRIPT". */
		public String scriptName = null;
		/** Appends '// address' to the end of every line. */
		public boolean showOffsets = false;
		/** Extension written in the {$CLEO .cs} directive. */
		public String format = ".cs";
		/** Header with the compiler directive. */
		public boolean header = true;
		/** Positive jump values are absolute addresses (main thread of main.scm); otherwise only non-positive values are jumps. */
		public boolean absoluteJumps = false;
		/**
		 * Range [mainStart, mainEnd) of the main thread of main.scm. With both >= 0 (and absoluteJumps=false), a positive
		 * address inside the range is emitted as @MAIN_<address> (a reference to a label of main.txt).
		 */
		public int mainStart = -1, mainEnd = -1;
		/** Addresses where a label is forced even if this script does not jump to them (other chunks use them). */
		public Set<Integer> forcedLabels = Collections.emptySet();
	}

	private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)([a-zA-Z]?)%");
	private static final Pattern SAFE_NAME = Pattern.compile("[^A-Za-z0-9_]");

	private static final int T_END = 0x0, T_INT32 = 0x1, T_GVAR = 0x2, T_LVAR = 0x3, T_INT8 = 0x4, T_INT16 = 0x5,
		T_FLOAT = 0x6, T_GARRAY = 0x7, T_LARRAY = 0x8, T_STR8 = 0x9, T_GSTRVAR = 0xA, T_LSTRVAR = 0xB,
		T_GSTRARRAY = 0xC, T_LSTRARRAY = 0xD, T_STRLEN = 0xE, T_STR16 = 0xF, T_LSTR16VAR = 0x10, T_GSTR16VAR = 0x11;

	private SCMLoader scm;
	private IDECollector ide_collector;
	private ArrayList<String> warnings = new ArrayList<>();
	private TreeSet<Integer> mainReferences = new TreeSet<>();

	private static class Arg {
		String text;
		boolean literal; // int8/int16/int32 (candidate for model/label)
		int value;
	}

	private static class Instr {
		int offset;      // position in the file
		int address;     // assigned address (base + offset - start)
		int id;          // includes the 0x8000 negation bit
		boolean invert;
		OpcodeInfo info;
		ArrayList<Arg> args = new ArrayList<>();
	}

	public ScriptDecompiler(SCMLoader scm, IDECollector ide) {
		this.scm = scm;
		this.ide_collector = ide;
	}

	/** Warnings of the last decompilation (unknown opcode, truncated data, ...). */
	public List<String> getWarnings() {
		return warnings;
	}

	/** Decompiles a whole file; with save_txt it writes <file>_decompiled.txt. */
	public String decompile(String file, boolean save_txt) {
		byte[] data;
		try {
			data = Files.readAllBytes(Paths.get(file));
		} catch(IOException e) {
			throw new UncheckedIOException("Cannot read " + file, e);
		}
		String decompiled = decompile(data, new Options());
		if(save_txt) {
			String out = file.replaceAll("\\.csa?$|\\.scm$", "") + "_decompiled.txt";
			try {
				Files.write(Paths.get(out), decompiled.getBytes(StandardCharsets.ISO_8859_1));
			} catch(IOException e) {
				throw new UncheckedIOException("Cannot write " + out, e);
			}
		}
		return decompiled;
	}

	/** Main thread addresses the last decompilation points to (see Options.mainStart). */
	public Set<Integer> getMainReferences() {
		return mainReferences;
	}

	public String decompile(byte[] data, Options opt) {
		warnings.clear();
		mainReferences.clear();
		int end = opt.end < 0 ? data.length : Math.min(opt.end, data.length);
		if(opt.start < 0 || opt.start > end) {
			throw new ScriptFormatException("Invalid range: start=" + opt.start + " end=" + end, opt.start);
		}
		ArrayList<Instr> code = decode(data, opt, end);
		String script_name = opt.scriptName;
		if(script_name == null) {
			script_name = "SCRIPT";
			for(Instr in : code) {
				if(in.id == 0x03A4 && in.args.size() > 0) {
					script_name = SAFE_NAME.matcher(in.args.get(0).text.replace("'", "")).replaceAll("_");
					break;
				}
			}
		}
		// jump addresses: <0 relative to the start of the script, >=0 absolute
		HashSet<Integer> addresses = new HashSet<>();
		int length = end - opt.start;
		for(Instr in : code) {
			for(int i = 0; i < in.args.size(); i++) {
				if(!isLabel(in, i)) {
					continue;
				}
				int value = in.args.get(i).value;
				int t = target(value);
				// 0 or negative: relative to the start; positive: only a jump if the script uses absolute addresses and falls inside the range
				if(value <= 0 || (opt.absoluteJumps && t >= opt.base && t < opt.base + length)) {
					in.args.get(i).text = "@" + script_name + "_" + t;
					addresses.add(t);
				} else if(opt.mainStart >= 0 && value >= opt.mainStart && value < opt.mainEnd) {
					in.args.get(i).text = "@MAIN_" + value;
					mainReferences.add(value);
				}
			}
		}
		addresses.addAll(opt.forcedLabels);
		StringBuilder out = new StringBuilder();
		if(opt.header) {
			out.append("// This file was decompiled using SASCM.ini\n{$CLEO ").append(opt.format).append("}\n\n");
		}
		for(Instr in : code) {
			if(addresses.contains(in.address)) {
				out.append("\n:").append(script_name).append("_").append(in.address).append("\n");
			}
			out.append(format(in));
			if(opt.showOffsets) {
				out.append(" // ").append(in.address);
			}
			out.append("\n");
		}
		return out.toString();
	}

	private static int target(int value) {
		return value < 0 ? -value : value;
	}

	private boolean isLabel(Instr in, int idx) {
		Arg a = in.args.get(idx);
		return a.literal && in.info.kindOf(idx) == 'p';
	}

	private String format(Instr in) {
		String line = (in.invert ? "not " : "") + stripComment(in.info.code_line);
		Matcher m = PLACEHOLDER.matcher(line);
		StringBuffer sb = new StringBuffer();
		int used = in.info.templateParams();
		while(m.find()) {
			int n = Integer.parseInt(m.group(1)) - 1;
			String text = n < in.args.size() ? in.args.get(n).text : "?";
			m.appendReplacement(sb, Matcher.quoteReplacement(text));
		}
		m.appendTail(sb);
		// parameters the template does not name (variadic): appended at the end
		for(int i = used; i < in.args.size(); i++) {
			sb.append(' ').append(in.args.get(i).text);
		}
		return String.format("%04X: %s", in.id, sb);
	}

	/** Strips the comments (; or //) of the .ini template: they must not reach the decompiled text. */
	private static String stripComment(String template) {
		int c = template.indexOf(';');
		int d = template.indexOf("//");
		if(c < 0 || (d >= 0 && d < c)) {
			c = d;
		}
		return c < 0 ? template : template.substring(0, c).replaceAll("\\s+$", "");
	}

	private ArrayList<Instr> decode(byte[] data, Options opt, int end) {
		ArrayList<Instr> code = new ArrayList<>();
		Cursor cur = new Cursor(data, opt.start, end);
		while(cur.pos + 2 <= end) {
			Instr in = new Instr();
			in.offset = cur.pos;
			in.address = opt.base + (cur.pos - opt.start);
			try {
				in.id = cur.ushort();
				in.invert = in.id > 0x7FFF;
				in.info = scm.get(in.id);
				if(in.info == null) {
					warnings.add(String.format("%d: unknown opcode %04X, decompilation stopped", in.address, in.id));
					break;
				}
				readArguments(cur, in);
			} catch(ScriptFormatException e) {
				warnings.add(String.format("%d: %s (opcode %04X), decompilation stopped", in.address, e.getMessage(), in.id));
				break;
			}
			code.add(in);
		}
		return code;
	}

	private void readArguments(Cursor cur, Instr in) {
		OpcodeInfo info = in.info;
		boolean variadic = info.isVariadic();
		int count = variadic ? info.templateParams() : info.param_count;
		for(int j = 0; j < count; j++) {
			in.args.add(readArgument(cur, info.kindOf(j)));
		}
		if(variadic) {
			// variadic opcodes end with a type byte of 0
			while(true) {
				if(cur.peek() == T_END) {
					cur.pos++;
					break;
				}
				in.args.add(readArgument(cur, ' '));
			}
		}
	}

	private Arg readArgument(Cursor cur, char kind) {
		Arg arg = new Arg();
		if(kind == 'k') {
			// the only case without a type byte: raw 128-byte text (05B6 save_string_to_debug_file)
			arg.text = "\"" + cur.string(128) + "\"";
			return arg;
		}
		int type = cur.ubyte();
		switch(type) {
			case T_INT32:
			case T_INT8:
			case T_INT16:
				arg.value = type == T_INT32 ? cur.int32() : (type == T_INT8 ? cur.sbyte() : cur.sshort());
				arg.literal = true;
				arg.text = Integer.toString(arg.value);
				if(kind == 'm' || kind == 'o') {
					String def = ide_collector.getDefinitionById(arg.value, false);
					if(def != null) {
						arg.text = "#" + def;
					}
				}
				break;
			case T_GVAR:
				arg.text = globalVar(cur.ushort());
				break;
			case T_LVAR:
				arg.text = cur.ushort() + "@";
				break;
			case T_FLOAT:
				arg.text = floatToText(cur.float32());
				break;
			case T_STR8:
				arg.text = "'" + cur.string(8) + "'";
				break;
			case T_STRLEN:
				arg.text = "\"" + cur.string(cur.ubyte()) + "\"";
				break;
			case T_STR16:
				arg.text = "'" + cur.string(16) + "'";
				break;
			case T_LSTR16VAR:
				arg.text = cur.ushort() + "@v";
				break;
			case T_GSTR16VAR:
				arg.text = "v$" + cur.ushort();
				break;
			case T_GSTRVAR:
				arg.text = "s$" + cur.ushort();
				break;
			case T_LSTRVAR:
				arg.text = cur.ushort() + "@s";
				break;
			case T_GARRAY:
			case T_LARRAY:
			case T_GSTRARRAY:
			case T_LSTRARRAY:
				arg.text = readArray(cur, type);
				break;
			default:
				throw new ScriptFormatException(String.format("unknown argument type 0x%02X", type), cur.pos - 1);
		}
		return arg;
	}

	/** base(index,size+type), e.g. $12(3@,25i) or 8@(2@,10f). Type: i int, f float, s text8, v text16. */
	private String readArray(Cursor cur, int type) {
		int offset = cur.ushort();
		int index = cur.ushort();
		int size = cur.ubyte();
		int flags = cur.ubyte();
		boolean global = type == T_GARRAY || type == T_GSTRARRAY;
		String base = global ? globalVar(offset) : offset + "@";
		String idx = (flags & 0x80) != 0 ? globalVar(index) : index + "@";
		char elem = "ifsv".charAt(flags & 3);
		if((flags & 0x7C) != 0) {
			warnings.add(String.format("array with unknown flags 0x%02X", flags));
		}
		return base + "(" + idx + "," + size + elem + ")";
	}

	private String globalVar(int offset) {
		String def = ide_collector.getDefinitionById(offset, true);
		return def != null ? def : "$" + (offset / 4);
	}

	/** Float in plain decimal notation (no exponent) that the compiler can read back. */
	static String floatToText(float f) {
		if(Float.isNaN(f) || Float.isInfinite(f)) {
			throw new ScriptFormatException("float cannot be represented: " + f, -1);
		}
		String s = new BigDecimal(Float.toString(f)).toPlainString();
		return s.indexOf('.') < 0 ? s + ".0" : s;
	}

	/** Little-endian reader with bounds checking. */
	private static class Cursor {
		final byte[] data;
		int pos;
		final int end;

		Cursor(byte[] data, int pos, int end) {
			this.data = data;
			this.pos = pos;
			this.end = end;
		}

		private void need(int n) {
			if(pos + n > end) {
				throw new ScriptFormatException("truncated data", pos);
			}
		}

		int peek() {
			need(1);
			return data[pos] & 0xFF;
		}

		int ubyte() {
			need(1);
			return data[pos++] & 0xFF;
		}

		int sbyte() {
			return (byte) ubyte();
		}

		int ushort() {
			need(2);
			int v = (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8;
			pos += 2;
			return v;
		}

		int sshort() {
			return (short) ushort();
		}

		int int32() {
			need(4);
			int v = (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
			pos += 4;
			return v;
		}

		float float32() {
			return Float.intBitsToFloat(int32());
		}

		/** Text of len bytes ending at the first 0 (or all of it if there is none). */
		String string(int len) {
			need(len);
			int n = 0;
			while(n < len && data[pos + n] != 0) {
				n++;
			}
			String s = new String(data, pos, n, StandardCharsets.ISO_8859_1);
			pos += len;
			return s;
		}
	}
}
