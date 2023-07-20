package cleo;
import java.util.*;
import java.io.*;

public class SCMLoader
{
	protected HashMap<Integer, OpcodeInfo> ops = new HashMap<Integer, OpcodeInfo>();
	
	public void load(String path) {
        try {
            // Crear un objeto BufferedReader para leer el archivo
            BufferedReader reader  = new BufferedReader(new FileReader(path));
            String line;
            while ((line = reader.readLine()) != null) {
				if(line.startsWith(";") || line.length() == 0 || line.startsWith("[") || line.startsWith("DATE")) {
					continue;
				}
                OpcodeInfo op = new OpcodeInfo();
				int idx_start = line.indexOf(',');
				String[] op_pc = line.substring(0, idx_start).split("=");
				int id = Integer.decode("0x"+op_pc[0]);
				op.param_count = Integer.parseInt(op_pc[1]);
				op.code_line = line.substring(idx_start + 1);
				ops.put(id, op);
            }
        } catch (IOException e) {
            System.err.println("Error al leer el archivo: " + e.getMessage());
        }
	}
}
