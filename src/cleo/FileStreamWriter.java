/*
 * CLEO SCript Java
 * FSSRepo 2023
 */

package cleo;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Little-endian writer. I/O errors are rethrown as UncheckedIOException. */
public class FileStreamWriter
{
	OutputStream out;

	public FileStreamWriter(String path){
		try{
			out = new FileOutputStream(path);
		}
		catch(IOException e){
			throw new UncheckedIOException("Cannot create " + path, e);
		}
	}

	/** Writes to an already open stream (for example a ByteArrayOutputStream). */
	public FileStreamWriter(OutputStream out) {
		this.out = out;
	}

	private void write(byte[] data){
		try{
			out.write(data);
		} catch(IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public void writeFloatArray(float[] data) {
		for (int ofs = 0; ofs < data.length; ofs++) {
			writeFloat(data[ofs]);
		}
	}

	public void writeByteArray(byte[] data) {
		write(data);
	}

	public void writeShortArray(short[] data) {
		for (int ofs = 0; ofs < data.length; ofs++) {
			writeShort(data[ofs]);
		}
	}

	public void writeString(String text) {
		writeByteArray(text.getBytes(StandardCharsets.ISO_8859_1));
	}

	/** String padded with zeros to size bytes (truncated if it does not fit). */
	public void writeStringFromSize(int size, String text) {
		byte[] data = new byte[size];
		byte[] src = text.getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(src, 0, data, 0, Math.min(size, src.length));
		writeByteArray(data);
	}

	public void writeFloat(float val) {
		writeInt(Float.floatToIntBits(val));
	}

	public void writeInt(int values) {
		byte[] data = new byte[4];
		data[0] = (byte) (values & 0xFF);
		data[1] = (byte) ((values >> 8) & 0xFF);
		data[2] = (byte) ((values >> 16) & 0xFF);
		data[3] = (byte) ((values >> 24) & 0xFF);
		write(data);
	}

	public void writeShort(int values) {
		byte[] data = new byte[2];
		data[0] = (byte) (values & 0xFF);
		data[1] = (byte) ((values >> 8) & 0xFF);
		write(data);
	}

	public void writeByte(int values) {
		write(new byte[]{ (byte) (values & 0xFF) });
	}

	public void finish(){
		try{
			out.close();
		}
		catch(IOException e){
			throw new UncheckedIOException(e);
		}
	}
}
