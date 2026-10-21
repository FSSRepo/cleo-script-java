/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.util.*;
import java.io.*;
import java.util.regex.*;

public class SCMLoader
{
	private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+)([a-zA-Z]?)%");

	protected HashMap<Integer, OpcodeInfo> ops = new HashMap<Integer, OpcodeInfo>();

	public void load(String path) {
		try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
			String line;
			int line_no = 0;
			while ((line = reader.readLine()) != null) {
				line_no++;
				if(line.startsWith(";") || line.length() == 0 || line.startsWith("[") || line.startsWith("DATE")) {
					continue;
				}
				int idx_start = line.indexOf(',');
				int idx_eq = line.indexOf('=');
				if(idx_start < 0 || idx_eq < 0 || idx_eq > idx_start) {
					throw new ScriptFormatException(path + ":" + line_no + ": invalid opcode line: " + line, line_no);
				}
				OpcodeInfo op = new OpcodeInfo();
				int id = Integer.parseInt(line.substring(0, idx_eq).trim(), 16);
				op.param_count = Integer.parseInt(line.substring(idx_eq + 1, idx_start).trim());
				op.code_line = line.substring(idx_start + 1);
				Matcher m = PLACEHOLDER.matcher(op.code_line);
				ArrayList<Integer> order = new ArrayList<>();
				ArrayList<Character> kinds = new ArrayList<>();
				while(m.find()) {
					order.add(Integer.parseInt(m.group(1)) - 1);
					kinds.add(m.group(2).isEmpty() ? ' ' : m.group(2).charAt(0));
				}
				op.placeholders = new int[order.size()];
				op.kinds = new char[order.size()];
				for(int i = 0; i < op.placeholders.length; i++) {
					op.placeholders[i] = order.get(i);
					op.kinds[i] = kinds.get(i);
				}
				ops.put(id, op);
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error reading " + path, e);
		}
	}

	/** Info of an opcode (without the 0x8000 negation bit); null if it does not exist. */
	public OpcodeInfo get(int id) {
		return ops.get(id & 0x7FFF);
	}
}
