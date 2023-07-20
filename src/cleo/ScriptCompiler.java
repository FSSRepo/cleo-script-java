/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.util.*;
import java.io.*;
import java.util.regex.*;

public class ScriptCompiler
{
	private SCMLoader scm;
	private HashMap<Integer, String> labels = new HashMap<>();
	private ArrayList<OpcodeProcessed> opcodes_processed = new ArrayList<OpcodeProcessed>();
	private ArrayList<OpcodeCompiled> opcodes_compiled = new ArrayList<OpcodeCompiled>();
	private HashMap<String, Integer> labels_addresses = new HashMap<>();
	private IDECollector ide_collector;
	public String error = "";
	public int line_idx = 0;
	
	private class OpcodeProcessed {
		public int id = 0;
		public int param_count = 0;
		public boolean invert = false;
		public int code_address = 0;
		public ArrayList<String> arguments = new ArrayList<>();
		public ArrayList<Integer> args_index = new ArrayList<Integer>();
	}
	
	private class OpParam {
		public int type;
		public String value;
		public int valuei;
		public float valuef;
		public boolean label;
	}
	
	private class OpcodeCompiled {
		public int id = 0;
		public OpParam[] params;
	}
	
	public ScriptCompiler(SCMLoader scm, IDECollector ide) {
		this.scm = scm;
		this.ide_collector = ide;
	}
	
	public boolean hasError() {
		return error.length() > 0;
	}
	
	public String compile(String path) {
		int size = 0;
		try {
            // Crear un objeto BufferedReader para leer el archivo
            BufferedReader reader  = new BufferedReader(new FileReader(path));
			String line;
			line_idx = 0;
			error = "";
			String format = ".cs";
            while ((line = reader.readLine()) != null) {
				line_idx++;
				if(line.length() == 0 || 
					line.startsWith("//")) {
					continue;
				}
				if(line.startsWith("{$")) {
					format = getFormat(line);
					continue;
				}
				if(line.contains("//")) {
					line = line.substring(0, line.indexOf("//"));
				}
                // check label -> code address
				if(line.startsWith(":")) {
					labels.put(line_idx + 1, line.replace(':','@'));
					continue;
				}
				OpcodeProcessed current = new OpcodeProcessed();
				String[] parts = line.split(":");
				current.id = Integer.decode("0x" + parts[0]);
				current.invert = current.id > 0x7FFF;
				current.code_address = line_idx;
				OpcodeInfo inf = scm.ops.get(current.invert ? (current.id - 0x8000) : current.id);
				if(inf == null) {
					// Unknown opcode
					return "Line "+line_idx+": '"+ parts[0] +"' Unknown opcode";
				}
				current.param_count = inf.param_count;
				// collect arguments
				if(collectArguments(current, inf.code_line, parts[1].replace(current.invert ? "not " : "", ""))) {
					opcodes_processed.add(current);
				} else {
					return error;
				}
            }
			// code to binary
			// calculate storage
			for(OpcodeProcessed op : opcodes_processed) {
				String label = labels.get(op.code_address);
				if(label != null) {
					labels_addresses.put(label, size);
				}
				//System.out.println(size + String.format(": Opcode: 0x%04X", op.id));
				OpcodeCompiled oc = new OpcodeCompiled();
				oc.id = op.id;
				size += 2; // opcode id
				oc.params = new OpParam[op.arguments.size()];
				for(int i = 0; i < op.arguments.size(); i ++) {
					size ++;
					OpParam param = new OpParam();
					int position = op.args_index.get(i);
					String arg = op.arguments.get(i);
					if(arg.charAt(0) == '@') {
						size += 4;
						param.type = 0x1;
						param.label = true;
						param.value = arg;
					} else if(arg.startsWith("s$")) {
						param.type = 0xA;
						size += 2;
						param.valuei = Integer.parseInt(arg.replace("s$",""));
					} else if(arg.startsWith("v$")) {
						param.type = 0x11;
						size += 2;
						param.valuei = Integer.parseInt(arg.replace("v$",""));
					} else if(arg.endsWith("@s")) {
						param.type = 0xB;
						size += 2;
						param.valuei = Integer.parseInt(arg.replace("@s",""));
					} else if(arg.endsWith("@v")) {
						param.type = 0x10;
						size += 2;
						param.valuei = Integer.parseInt(arg.replace("@v",""));
					} else if(op.id == 0x00D6) {
						size += 1;
						param.type = 0x04;
						param.valuei = Integer.parseInt(arg);
					} else if(checkFormat(arg, "\\d{1,2}@")) {
						param.type = 0x3;
						size += 2;
						param.valuei = Integer.parseInt(arg.replace("@",""));
					} else if(arg.charAt(0) == '$') {
						param.type = 0x2;
						size += 2;
						int fd = ide_collector.getIdByDefinition(arg);
						if(fd != -1) {
							param.valuei = fd;
						} else {
							String test = arg.replace("$", "");
							if(!checkIfInteger(test)) {
								error = "Line "+line_idx+": invalid global variable '"+arg+"'";
								return error;
							}
							param.valuei =  Integer.parseInt(test);
						}
					} else if(checkIfInteger(arg)) {
						param.valuei = Integer.parseInt(arg);
						param.type = (Math.abs(param.valuei) < 128 ? 0x4 : 
							(Math.abs(param.valuei) > 32735 ? 0x1 : 
							0x5));
						size += param.type == 0x4 ? 1 : (param.type == 0x5 ? 2 : 4);
					} else if(checkIfFloat(arg)) {
						param.type = 0x6;
						size += 4;
						param.valuef = Float.parseFloat(arg);
					} else if(checkFormat(arg, "'([^']*)'")) {
						param.value = arg.replace("'","");
						param.type = (byte)(param.value.length() > 8 ? 0xF : 0x9);
						size += (param.value.length() > 8 ? 16 : 8);
					} else if(checkFormat(arg, "\"([^']*)\"")) {
						param.value = arg.replace("\"","");
						param.type = 0xE;
						size += 1 + param.value.length();
					}
					oc.params[position] = param;
				}
				opcodes_compiled.add(oc);
			}
			opcodes_processed.clear();
			// write
			FileStreamWriter fwr = new FileStreamWriter(path.replaceAll(".txt", format));
			for(OpcodeCompiled oc : opcodes_compiled) {
				fwr.writeShort(oc.id);
				for(OpParam param : oc.params) {
					fwr.writeByte(param.type);
					if(param.label) {
						fwr.writeInt( - labels_addresses.get(param.value));
						continue;
					}
					switch(param.type) {
						case 0x1: // int value
							fwr.writeInt(param.valuei);
							break;
						case 0x2: // global var
						case 0x3: // local var
						case 0xA: // global string 8 var
						case 0xB: // local string 8 var
						case 0x10: // global string 16 var
						case 0x11: // local string 16 var
						case 0x5: // short value
							fwr.writeShort(param.valuei);
							break;
						case 0x6: // float value
							fwr.writeFloat(param.valuef);
							break;
						case 0x4: // byte value
							fwr.writeByte(param.valuei);
							break;
						case 0x9: // string 8
							fwr.writeStringFromSize(8, param.value);
							break;
						case 0xF: // string 16
							fwr.writeStringFromSize(16, param.value);
							break;
						case 0xE: // string 
							fwr.writeByte(param.value.length());
							fwr.writeString(param.value);
							break;
					}
				}
			}
			fwr.finish();
        } catch (Exception e) {
			StringWriter sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
            return "Compile Error:\n" + sw.toString()+"\nLine "+line_idx+" breaked";
        }
		return "Script Length: "+ size;
	}
	
	private static String getFormat(String input) {
		// Creamos el objeto Pattern y Matcher
        Pattern regex = Pattern.compile("\\.cs[\\w]*");
        Matcher matcher = regex.matcher(input);
        // Realizamos la búsqueda del patrón en el input
        return matcher.find() ? matcher.group() : "";
    }
	
	private static boolean checkFormat(String input, String pattern) {
       // Creamos el objeto Pattern y Matcher
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(input);
        // Realizamos la búsqueda del patrón en el input
        return matcher.matches();
    }
	
	private static boolean checkIfInteger(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
	
	private static boolean checkIfFloat(String input) {
        try {
            Float.parseFloat(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
	
	private boolean collectArguments(OpcodeProcessed current, String reference, String code) {
		String pattern_arguments = "[sv][$@]\\d+|\\$\\w+|\\#\\w+|\\@\\w+|\\s\\d+\\@|\\s\\d+\\.\\d+|-[\\d.]+|\\s\\d+|'(.*?)'|\"(.*?)\"";
        Pattern pattern = Pattern.compile(pattern_arguments);
		Pattern pattern_ref = Pattern.compile("%(\\d+[a-zA-Z]?)%");
		Matcher matcher = pattern.matcher(code);
		while (matcher.find()) {
			String argument = matcher.group().replace(" ","");
			if(argument.charAt(0) != '#') {
				current.arguments.add(argument);
			} else {
				int fd = ide_collector.getIdByDefinition(argument.substring(1));
				if(fd == -1) {
					error = "Line "+line_idx+": invalid definition '" + argument + "'";
					return false;
				}
				current.arguments.add(fd +"");
			}
		}
		if(current.id == 0x00D6) {
			if(current.arguments.size() == 0) {
				current.arguments.add("0");
			}
			int num_conditions = !checkIfInteger(current.arguments.get(0)) ? -1 : Integer.parseInt(current.arguments.get(0));
			if(num_conditions == -1 || num_conditions < 0 || 
				num_conditions > 7 && num_conditions < 21 || 
				num_conditions > 27) {
				error = "Line "+line_idx+": invalid if argument\n1 ... 7 AND operator, 21 ... 27 OR operator, please specify the number of conditions.";
				return false;
			}
		}
		Matcher matcher_ref = pattern_ref.matcher(reference);
		while (matcher_ref.find()) {
			String value = matcher_ref.group(1);
			current.args_index.add(Integer.parseInt(value.replaceAll("[a-zA-Z]", "")) - 1);
		}
		if(current.param_count != -1 && current.arguments.size() != current.param_count) {
			error = "Line "+line_idx+": missing parameters, expected "+current.param_count + ", given "+ current.arguments.size();
			return false;
		}
		return true;
	}
}
