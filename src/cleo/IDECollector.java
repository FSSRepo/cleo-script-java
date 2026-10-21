/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.util.*;
import java.io.*;

public class IDECollector {
	public static class IDEItem {
		public int id;
		public String dff;
		boolean global_var = false;
	}

	public ArrayList<IDEItem> items = new ArrayList<>();
	// indexes for O(1) lookups; the first entry loaded for an id/name wins
	private HashMap<Integer, String> by_model_id = new HashMap<>();
	private HashMap<Integer, String> by_global_id = new HashMap<>();
	private HashMap<String, Integer> by_name = new HashMap<>();
	private HashMap<String, Integer> by_global_name = new HashMap<>();

	public void load(String path) {
		try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
			String line;
			boolean collect = false, global_var = false;
			while ((line = reader.readLine()) != null) {
				line = line.replaceAll("\\s|\t","");
				if(line.startsWith("#") ||
					line.length() == 0) {
					continue;
				}
				if(!collect) {
					global_var = line.startsWith("globals");
					collect = global_var || line.matches("weap|cars|peds");
				} else if(line.startsWith("end")) {
					collect = false;
					global_var = false;
					continue;
				} else {
					String[] parts = line.split(",");
					IDEItem ide_itm = new IDEItem();
					ide_itm.id = Integer.parseInt(parts[0]);
					ide_itm.dff = parts[1].replace(" ","").toUpperCase();
					ide_itm.global_var = global_var;
					items.add(ide_itm);
					(global_var ? by_global_id : by_model_id).putIfAbsent(ide_itm.id, ide_itm.dff);
					by_name.putIfAbsent(ide_itm.dff, ide_itm.id);
					if(global_var) {
						by_global_name.putIfAbsent(ide_itm.dff, ide_itm.id);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error reading " + path, e);
		}
	}

	/**
	 * Loads global variable names in Sanny Builder's CustomVariables.ini format ("2=PLAYER_CHAR" means $2 is $PLAYER_CHAR).
	 * Names keep their case. Entries already defined (for example by globals.ide) are not replaced.
	 */
	public void loadVariables(String path) {
		try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.trim();
				int eq = line.indexOf('=');
				if(line.isEmpty() || line.startsWith(";") || eq <= 0) {
					continue;
				}
				String name = "$" + line.substring(eq + 1).trim();
				int offset = Integer.parseInt(line.substring(0, eq).trim()) * 4;
				IDEItem item = new IDEItem();
				item.id = offset;
				item.dff = name;
				item.global_var = true;
				if(!by_global_id.containsKey(offset) && !by_global_name.containsKey(name)) {
					items.add(item);
					by_global_id.put(offset, name);
					by_global_name.put(name, offset);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Error reading " + path, e);
		}
	}

	/** Offset of a global variable by its exact name (with the leading '$'); -1 if unknown. */
	public int getGlobalIdByName(String name) {
		Integer id = by_global_name.get(name);
		return id == null ? -1 : id;
	}

	public String getDefinitionById(int id, boolean global) {
		return (global ? by_global_id : by_model_id).get(id);
	}

	/** Id of a definition: exact match first, otherwise the first name that starts with def. */
	public int getIdByDefinition(String def) {
		Integer exact = by_name.get(def);
		if(exact != null) {
			return exact;
		}
		for(IDEItem ide : items) {
			if(ide.dff.startsWith(def)){
				return ide.id;
			}
		}
		return -1;
	}
}
