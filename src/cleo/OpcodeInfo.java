/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;

public  class OpcodeInfo {
	/** Number of fixed parameters; -1 if the opcode is variadic (the list ends with a 0x00 byte). */
	public int param_count;
	public String code_line;
	/** Parameters named by the template (%1d%, %2p%, ...), in order of appearance. */
	public int[] placeholders = new int[0];
	/** Letter of each placeholder (d, p, h, m, o, s, g, x, ...); ' ' if it has none. Parallel to placeholders. */
	public char[] kinds = new char[0];

	/** Number of parameters covered by the template (highest placeholder index + 1). */
	public int templateParams() {
		int max = 0;
		for(int p : placeholders) {
			max = Math.max(max, p + 1);
		}
		return max;
	}

	/** Placeholder letter of parameter idx (' ' if none). */
	public char kindOf(int idx) {
		for(int i = 0; i < placeholders.length; i++) {
			if(placeholders[i] == idx) {
				return kinds[i];
			}
		}
		return ' ';
	}

	public boolean isVariadic() {
		return param_count == -1;
	}
}
