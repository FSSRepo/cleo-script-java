/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.util.*;
import java.util.regex.*;

public class ScriptDecompiler
{
	private SCMLoader scm;
	private byte[] data;
	private int offset = 0;
	private Matcher match;
	private String script_name = "SCRIPT";
	private ArrayList<OpcodeDecompiled> opcode_decompiled = new ArrayList<OpcodeDecompiled>();
	private OpcodeDecompiled current;
	private ArrayList<Integer> addresses = new ArrayList<Integer>();
	private ArrayList<String> arguments = new ArrayList<>();
	private IDECollector ide_collector;
	
	public class OpcodeDecompiled {
		public int id = 0;
		public int address = 0;
		public int param_count = 0;
		public boolean invert = false;
		public String decompiled_line = "";
	}
	
	public ScriptDecompiler(SCMLoader scm, IDECollector ide) {
		this.scm = scm;
		this.ide_collector = ide;
	}
	
	public String decompile(String file, boolean save_txt) {
		// load cleo data
		try{
			InputStream is = new FileInputStream(file);
			data = new byte[is.available()];
			is.read(data);
			is.close();
			is = null;
		} catch(Exception e){ }
		String decompiled = "// This file was decompiled using SASCM.ini\n{$CLEO .cs}\n\n";
		
		// decompile
		while(true) {
			if(offset + 2 > data.length || !readOpcode()) {
				break;
			}
			readArguments();
			performDecompile();
		}
		for(OpcodeDecompiled oc : opcode_decompiled) {
			if(addresses.indexOf(oc.address) != -1) {
				decompiled += "\n:" + script_name + "_" + oc.address+"\n";
			}
			decompiled += oc.decompiled_line + "\n";
		}
		if(save_txt) {
			try{
				FileOutputStream os = new FileOutputStream(file.replaceAll(".csa|.cs","_decompiled.txt"));
				os.write(decompiled.getBytes());
				os.close();
			} catch(Exception e){ }
		}
		return decompiled;
	}
	
	private void performDecompile() {
		String final_line = (current.invert ? "not " : "") + current.decompiled_line;
		if(arguments.size() == 0) {
			current.decompiled_line = String.format("%04X: %s", current.id, final_line);
		} else {
			String re = "(%1\\w%)";
			int i = 1;
			while(matchInput(final_line, re)) {
				String argId = match.group(0);
				if(arguments.size() > 0) {
					String arg = arguments.remove(0);
					if(checkIfInteger(arg) && (argId.contains("m") || argId.contains("o"))) {
						String def = ide_collector.getDefinitionById(Integer.parseInt(arg), false);
						if(def != null) {
							arg = "#" + def;
						}
					}
					final_line = final_line.replace(argId, arg);
				}
				re = "(%"+ (++i) +"\\w%)";
			}
			current.decompiled_line = String.format("%04X: %s", current.id, final_line);
		}
	}
	
	public boolean readOpcode() {
		current = new OpcodeDecompiled();
		current.address = offset;
		current.id = readUShort();
		current.invert = current.id > 0x7FFF;
		OpcodeInfo inf = scm.ops.get(current.invert ? (current.id - 0x8000) : current.id);
		if(inf == null) {
			return false;
		}
		current.param_count = inf.param_count;
		current.decompiled_line = inf.code_line.replace(";","//");
		opcode_decompiled.add(current);
		return true;
	}
	
	public void readArguments() {
		arguments.clear();
		// read arguments
		if(current.param_count != -1) {
			for(int j = 0; j < current.param_count; j++) {
				String argument = readArgument();
				if(current.id == 0x0D6) { // if must have one argument
					int val = Integer.parseInt(argument);
					if(val == 0) {
						argument = "";
					}
				} else if(current.id == 0x03A4) { // get thread name
					script_name = argument.replaceAll("'","");
				} else if(current.id == 0x002 || current.id == 0x04D) {
					int address = Integer.parseInt(argument) * -1;
					addresses.add(address);
					argument = "@"+script_name+"_"+address;
				}
				arguments.add(argument);
			}
		} else {
			while(true) {
				String argument = readArgument();
				if(argument.length() == 0) {
					break;
				}
			}
		}
	}
	
	public String readArgument() {
		int type = readByte();
		String arg = "";
		switch(type) {
			case 0x1: // int type
				arg = readInt() + "";
				break;
			case 0x2: // global var
				int glb = readUShort();
				String fd = ide_collector.getDefinitionById(glb, true);
				arg =  fd == null ?  "$" + glb : fd;
				break;
			case 0x3: // local var
				arg = readUShort() + "@";
				break;
			case 0x4: // byte type
				arg = readByte() +"";
				break;
			case 0x5: // short var
				arg = readShort()+"";
				break;
			case 0x6: // float var
				arg = readFloat() +"";
				break;
			case 0x9: // string 8
				arg = "'" + readString(8) + "'";
				break;
			case 0xE: // string
				arg = "\"" + readString(readByte()) + "\"";
				break;
			case 0xF: // string 16
				arg = "'" + readString(16) + "'";
				break;
			case 0x10: // local string 16
				arg = readUShort() + "@v";
				break;
			case 0x11: // global string 16
				arg = "v$" + readUShort();
				break;
			case 0xA: // global string 8
				arg = "s$" + readUShort();
				break;
			case 0xB: // local string 8
				arg = readUShort() + "@s";
				break;
		}
		return arg;
	}
	
	public String readString(int len){
		return cortarnombre(new String(readByteArray(len)));
	}
	
	private String cortarnombre(String str) {
        int indexOf = str.indexOf(0);
        return indexOf > 0 ? str.substring(0, indexOf) : str;
    }
	
	public int readUShort() {
		int val = ((data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8);
		offset += 2;
		return val;
	}

	public short readShort() {
		short val = (short)((data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8);
		offset += 2;
		return val;
	}
	
	public byte[] readByteArray(int len) {
		byte[] bdata = new byte[len];
        for (int i = 0; i < len; i++) {
            bdata[i] = data[offset];
			offset++;
        }
        return bdata;
	}
	public byte readByte() {
		byte val = data[offset];
        offset++;
        return val;
	}
	public int readInt() {
		int val = (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16 | (data[offset + 3] & 0xFF) << 24;
        offset += 4;
        return val;
	}
	
	private static boolean checkIfInteger(String input) {
        try {
            Integer.parseInt(input);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
	
	public float readFloat() {
		return Float.intBitsToFloat(readInt());
	}
	
	private boolean matchInput(String input, String regex) {
		Pattern prtn = Pattern.compile(regex);
		match = prtn.matcher(input);
		return match.matches() || match.find();
	}
}
