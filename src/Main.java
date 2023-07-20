import java.util.*;
import java.io.*;
import cleo.*;
import java.util.regex.*;

public class Main
{
	public static void main(String[] args)
	{
		IDECollector ide_col = new IDECollector();
		ide_col.load("res/vehicles.ide");
		ide_col.load("res/peds.ide");
		ide_col.load("res/default.ide");
		ide_col.load("res/globals.ide");
		SCMLoader scm = new SCMLoader();
		scm.load("res/SASCM.INI");
		ScriptCompiler dec = new ScriptCompiler(scm, ide_col);
		System.out.println(dec.compile("res/bigfoot.txt"));
		ScriptDecompiler deco = new ScriptDecompiler(scm, ide_col);
		deco.decompile("res/cheats.cs", true);
	}
}
