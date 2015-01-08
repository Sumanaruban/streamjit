package edu.mit.streamjit.impl.distributed.common;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Collections;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.common.IOInfo;

/**
 * @author Sumanan sumanan@mit.edu
 * @since Jul 30, 2013
 */
public class Utils {

	public static Token getBlobID(Blob b) {
		return Collections.min(b.getInputs());
	}

	public static Token getblobID(Set<Worker<?, ?>> workers) {
		ImmutableSet.Builder<Token> inputBuilder = new ImmutableSet.Builder<>();
		for (IOInfo info : IOInfo.externalEdges(workers)) {
			if (info.isInput())
				inputBuilder.add(info.token());
		}

		return Collections.min(inputBuilder.build());
	}

	/**
	 * Prints heapMaxSize, current heapSize and heapFreeSize.
	 */
	public static void printMemoryStatus() {
		long heapMaxSize = Runtime.getRuntime().maxMemory();
		long heapSize = Runtime.getRuntime().totalMemory();
		long heapFreeSize = Runtime.getRuntime().freeMemory();
		int MEGABYTE = 1024 * 1024;
		System.out.println("#########################");
		System.out.println(String.format("heapMaxSize = %dMB", heapMaxSize
				/ MEGABYTE));
		System.out.println(String
				.format("heapSize = %dMB", heapSize / MEGABYTE));
		System.out.println(String.format("heapFreeSize = %dMB", heapFreeSize
				/ MEGABYTE));
		System.out.println("#########################");
	}

	public static void printOutOfMemory() {
		MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
		System.out.println("******OutOfMemoryError******");
		MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
		int MEGABYTE = 1024 * 1024;
		long maxMemory = heapUsage.getMax() / MEGABYTE;
		long usedMemory = heapUsage.getUsed() / MEGABYTE;
		System.out
				.println("Memory Use :" + usedMemory + "M/" + maxMemory + "M");
	}

	/**
	 * @param name
	 *            name of the directory.
	 * @return <code>true</code> if and only if the directory was created; false
	 *         otherwise.
	 */
	private static boolean createDir(String name) {
		File dir = new File(name);
		if (dir.exists()) {
			if (dir.isDirectory())
				return true;
			else {
				System.err.println("A file exists in the name of dir-" + name);
				return false;
			}
		} else
			return dir.mkdir();
	}

	/**
	 * @param name
	 *            name of the directory.
	 * @return <code>true</code> if and only if the directory was created; false
	 *         otherwise.
	 */
	public static boolean createAppDir(String appName) {
		return createDir(appName);
	}
}
