/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Structure of a GTA SA main.scm: a chain of segments (each starts with 'GOTO next' + id),
 * the mission table and the code of the main thread.
 *
 * Segment 0 (no id): global variables; id 0 = object names; 1 = mission table;
 * 2 = external scripts; 3 = empty segment; 4 = (globals size - 8, threads memory). After the last GOTO
 * the main thread code starts. Missions are loaded into memory as a script of their own, so their
 * jumps are relative to their start (negative); those of the main thread are absolute file addresses.
 */
public class ScmFile
{
	public static final int NAME_SIZE = 24, EXTERNAL_NAME_SIZE = 20, EXTERNAL_ENTRY_SIZE = 28;

	public final byte[] data;
	/** Start of the main thread code. */
	public int mainStart = -1;
	/** End of the main code = start of the first mission. */
	public int mainSize;
	public int largestMission;
	public int exclusiveMissions;
	public int localVars;
	/** File offset of every mission. */
	public int[] missions = new int[0];
	/** Start of every segment found and its id (in order). */
	public int[] segmentStart = new int[0];
	public int[] segmentId = new int[0];

	/** Size of the global variable space (= start of the object segment). */
	public int globalsSize;
	/** The only non-zero byte of the global variable space (offset 7). */
	public int globalsByte7;
	/** Object names (24 bytes each, without the zero padding). */
	public String[] objects = new String[0];
	public int largestExternal;
	/** External scripts: name, virtual offset (after main.scm) and size. */
	public String[] externalNames = new String[0];
	public int[] externalOffsets = new int[0];
	public int[] externalSizes = new int[0];
	/** Value of the empty segment (DEFINE UNKNOWN_EMPTY_SEGMENT). */
	public int emptySegment;
	/** First value of the last segment (always globalsSize - 8). */
	public int variablesSpace;
	/** Second value of the last segment (DEFINE UNKNOWN_THREADS_MEMORY). */
	public int threadsMemory;

	public ScmFile(byte[] data) {
		this.data = data;
		parse();
	}

	public static ScmFile load(String path) {
		try {
			return new ScmFile(Files.readAllBytes(Paths.get(path)));
		} catch(IOException e) {
			throw new UncheckedIOException("Cannot read " + path, e);
		}
	}

	private int i32(int pos) {
		if(pos < 0 || pos + 4 > data.length) {
			throw new ScriptFormatException("main.scm truncated at " + pos, pos);
		}
		return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
	}

	private int u16(int pos) {
		if(pos < 0 || pos + 2 > data.length) {
			throw new ScriptFormatException("main.scm truncated at " + pos, pos);
		}
		return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8;
	}

	private boolean isGoto(int pos) {
		return pos >= 0 && pos + 8 <= data.length && data[pos] == 0x02 && data[pos + 1] == 0x00 && data[pos + 2] == 0x01;
	}

	private String cstring(int pos, int max) {
		int n = 0;
		while(n < max && data[pos + n] != 0) {
			n++;
		}
		return new String(data, pos, n, java.nio.charset.StandardCharsets.ISO_8859_1);
	}

	private void parse() {
		int pos = 0;
		int[] starts = new int[16], ids = new int[16];
		int n = 0;
		while(isGoto(pos) && n < starts.length) {
			int id = data[pos + 7] & 0xFF;
			starts[n] = pos;
			ids[n] = id;
			n++;
			if(n > 1) {
				readSegment(pos, id);
			}
			int next = i32(pos + 3);
			if(next <= pos) {
				break;
			}
			pos = next;
		}
		segmentStart = Arrays.copyOf(starts, n);
		segmentId = Arrays.copyOf(ids, n);
		mainStart = pos;
		if(n > 1) {
			globalsSize = starts[1];
			globalsByte7 = data[7] & 0xFF;
		}
	}

	private void readSegment(int pos, int id) {
		switch(id) {
			case 0: {
				int count = i32(pos + 8);
				objects = new String[count];
				for(int i = 0; i < count; i++) {
					objects[i] = cstring(pos + 12 + i * NAME_SIZE, NAME_SIZE);
				}
				break;
			}
			case 1: {
				// mission table: main size, largest mission, mission count, exclusive missions, local variables, offsets[]
				mainSize = i32(pos + 8);
				largestMission = i32(pos + 12);
				int count = u16(pos + 16);
				exclusiveMissions = u16(pos + 18);
				localVars = i32(pos + 20);
				missions = new int[count];
				for(int i = 0; i < count; i++) {
					missions[i] = i32(pos + 24 + i * 4);
				}
				break;
			}
			case 2: {
				largestExternal = i32(pos + 8);
				int count = i32(pos + 12);
				externalNames = new String[count];
				externalOffsets = new int[count];
				externalSizes = new int[count];
				for(int i = 0; i < count; i++) {
					int q = pos + 16 + i * EXTERNAL_ENTRY_SIZE;
					externalNames[i] = cstring(q, EXTERNAL_NAME_SIZE);
					externalOffsets[i] = i32(q + EXTERNAL_NAME_SIZE);
					externalSizes[i] = i32(q + EXTERNAL_NAME_SIZE + 4);
				}
				break;
			}
			case 3:
				emptySegment = i32(pos + 8);
				break;
			case 4:
				variablesSpace = i32(pos + 8);
				threadsMemory = i32(pos + 12);
				break;
			default:
				break;
		}
	}

	public int missionCount() {
		return missions.length;
	}

	/** Start (file offset) of mission n. */
	public int missionStart(int n) {
		check(n);
		return missions[n];
	}

	/** End (exclusive) of mission n: start of the next one or end of the file. */
	public int missionEnd(int n) {
		check(n);
		return n + 1 < missions.length ? missions[n + 1] : data.length;
	}

	private void check(int n) {
		if(n < 0 || n >= missions.length) {
			throw new ScriptFormatException("mission " + n + " out of range (0.." + (missions.length - 1) + ")", -1);
		}
	}
}
