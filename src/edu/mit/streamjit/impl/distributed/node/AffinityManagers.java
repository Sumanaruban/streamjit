package edu.mit.streamjit.impl.distributed.node;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;

import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.distributed.common.Machine;
import edu.mit.streamjit.impl.distributed.common.Utils;
import edu.mit.streamjit.util.ConfigurationUtils;

/**
 * Various implementations of the interface {@link AffinityManager}.
 * 
 * @author sumanan
 * @since 4 Feb, 2015
 */
public class AffinityManagers {

	/**
	 * This is an empty {@link AffinityManager}.
	 * {@link #getAffinity(Token, int)} always returns null.
	 * 
	 * @author sumanan
	 * @since 4 Feb, 2015
	 */
	public static class EmptyAffinityManager implements AffinityManager {

		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			return null;
		}
	}

	/**
	 * Allocates only one core per blob. All threads of that blob must use that
	 * single core.
	 * 
	 * @author sumanan
	 * @since 17 Nov, 2015
	 */
	public static class OneCoreAffinityManager implements AffinityManager {

		int currentCoreToAllocate = 0;

		private final int totalCores;

		public OneCoreAffinityManager() {
			this(Runtime.getRuntime().availableProcessors());
		}

		public OneCoreAffinityManager(int totalCores) {
			this.totalCores = totalCores;
		}

		Map<Token, Integer> blobCoreMap = new HashMap<>();
		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			if (!blobCoreMap.containsKey(blobID)) {
				int c = currentCoreToAllocate++ % totalCores;
				blobCoreMap.put(blobID, c);
			}
			return ImmutableSet.of(blobCoreMap.get(blobID));
		}
	}

	/**
	 * Equally divide the available cores to the blobs. Threads of a blob share
	 * all cores that are allocated to that blob.
	 * 
	 * @author sumanan
	 * @since 17 Nov, 2015
	 */
	public static class EqualAffinityManager implements AffinityManager {

		private final int totalBlobs;

		private final int totalCores;

		Map<Token, List<Integer>> blobCoreMap = new HashMap<>();

		int currentCoreToAllocate = 0;

		final int coresPerBlob;

		int reminder;

		EqualAffinityManager(int totalBlobs) {
			this(totalBlobs, Runtime.getRuntime().availableProcessors());
		}

		EqualAffinityManager(int totalBlobs, int totalCores) {
			if (totalBlobs == 0)
				totalBlobs = 1;
			this.totalCores = totalCores;
			this.totalBlobs = totalBlobs;
			coresPerBlob = totalCores / totalBlobs;
			reminder = totalCores % totalBlobs;
		}

		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			if (!blobCoreMap.containsKey(blobID))
				blobCoreMap.put(blobID, newAllocation());
			return ImmutableSet.copyOf(blobCoreMap.get(blobID));
		}

		private List<Integer> newAllocation() {
			if (totalBlobs < totalCores) {
				List<Integer> l = new ArrayList<>();
				for (int i = 0; i < coresPerBlob; i++)
					l.add(currentCoreToAllocate++);
				if (reminder > 0) {
					l.add(currentCoreToAllocate++);
					reminder--;
				}
				return l;
			} else {
				int c = currentCoreToAllocate++ % totalCores;
				return ImmutableList.of(c);
			}
		}
	}

	public static class CoreCodeAffinityManager implements AffinityManager {

		public static final int physicalCores = Machine.sockets
				* Machine.coresPerSocket;

		private final Set<Blob> blobSet;

		private final ImmutableTable<Token, Integer, Integer> assignmentTable;

		private final int totalRequiredCores;

		private final Map<Token, Blob> blobIdBlobMap;

		/**
		 * Free cores in each socket.
		 */
		private final Map<Integer, Integer> freeCoresMap;

		CoreCodeAffinityManager(Set<Blob> blobSet) {
			this.blobSet = blobSet;
			freeCoresMap = freeCores();
			totalRequiredCores = totalRequiredCores(blobSet);
			blobIdBlobMap = blobIdBlobMap(blobSet);
			assignmentTable = assign();
			printTable(assignmentTable);
		}

		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			return ImmutableSet.of(assignmentTable.get(blobID, coreCode));
		}

		private int totalRequiredCores(Set<Blob> blobSet) {
			int cores = 0;
			for (Blob b : blobSet)
				cores += b.getCoreCount();
			return cores;
		}

		private Map<Token, Blob> blobIdBlobMap(Set<Blob> blobSet) {
			Map<Token, Blob> m = new HashMap<>();
			for (Blob b : blobSet) {
				Token blobID = Utils.getBlobID(b);
				m.put(blobID, b);
			}
			return m;
		}

		private ImmutableTable<Token, Integer, Integer> assign() {
			Map<Blob, Integer> coresPerBlob = coresPerBlob();
			Map<Blob, Set<Integer>> coresForBlob = coresForBlob(coresPerBlob);

			ImmutableTable.Builder<Token, Integer, Integer> tBuilder = ImmutableTable
					.builder();
			for (Map.Entry<Blob, Set<Integer>> en : coresForBlob.entrySet()) {
				Blob b = en.getKey();
				Token t = Utils.getBlobID(b);
				List<Integer> cores = new ArrayList<Integer>(en.getValue());
				for (int i = 0; i < b.getCoreCount(); i++) {
					int coreIndex = i % cores.size();
					int round = i / cores.size();
					int coreID = round % 2 == 0 ? cores.get(coreIndex)
							: getCorrespondingVirtualCoreID(cores
									.get(coreIndex));
					tBuilder.put(t, i, coreID);
				}
			}
			return tBuilder.build();
		}

		private Map<Integer, Integer> freeCores() {
			Map<Integer, Integer> m = new HashMap<Integer, Integer>(
					Machine.sockets);
			for (int i = 0; i < Machine.sockets; i++)
				m.put(i, Machine.coresPerSocket);
			return m;
		}

		private Map<Blob, Integer> coresPerBlob() {
			int free = currentFreeCores();
			if (blobSet.size() >= free)
				return OneCorePerBlob();
			else if (totalRequiredCores > free)
				return proportionalAllocation();
			else
				return FullAllocation();
		}

		/**
		 * Use this allocation if the total blobs are greater or equal than the
		 * total physical cores.
		 * 
		 * @return
		 */
		private Map<Blob, Integer> OneCorePerBlob() {
			Map<Blob, Integer> coresPerBlob = new HashMap<>();
			for (Blob b : blobSet) {
				coresPerBlob.put(b, 1);
			}
			return coresPerBlob;
		}

		/**
		 * Use this allocation if the total physical cores are greater or equal
		 * than the total required cores.
		 * 
		 * @return
		 */
		private Map<Blob, Integer> FullAllocation() {
			Map<Blob, Integer> coresPerBlob = new HashMap<>();
			for (Blob b : blobSet) {
				coresPerBlob.put(b, b.getCoreCount());
			}
			return coresPerBlob;
		}

		/**
		 * Use this allocation if the total physical cores are lesser than the
		 * total required cores.
		 * 
		 * @return
		 */
		private Map<Blob, Integer> proportionalAllocation() {
			Map<Blob, Integer> coresPerBlob = new HashMap<>();
			Set<Blob> unsatisfiedBlobs = new HashSet<>();
			int allocatedCores = 0;
			for (Blob b : blobSet) {
				int coreCode = b.getCoreCount();
				int p = Math.max(1, (coreCode * physicalCores)
						/ totalRequiredCores);
				coresPerBlob.put(b, p);
				allocatedCores += p;
				if (coreCode > p)
					unsatisfiedBlobs.add(b);
			}
			int remainingCores = physicalCores - allocatedCores;
			assignRemainingCores(coresPerBlob, remainingCores, unsatisfiedBlobs);
			return coresPerBlob;
		}

		private void assignRemainingCores(Map<Blob, Integer> coresPerBlob,
				int remainingCores, Set<Blob> unsatisfiedBlobs) {
			if (remainingCores < 1)
				return;
			List<Blob> blobList = new ArrayList<>(unsatisfiedBlobs);
			Collections.sort(
					blobList,
					(b1, b2) -> Integer.compare(b1.getCoreCount(),
							b2.getCoreCount()));
			for (Blob b : blobList) {
				if (remainingCores == 0)
					break;
				int p = coresPerBlob.get(b);
				coresPerBlob.replace(b, p + 1);
				remainingCores--;
			}
		}

		private Map<Blob, Set<Integer>> coresForBlob(
				Map<Blob, Integer> coresPerBlob) {
			Map<Blob, Set<Integer>> coresForBlob = new HashMap<>();

			for (int i = 0; i < Machine.sockets; i++) {
				for (int j = Machine.coresPerSocket; j > 0; j--) {
					List<Map<Blob, Integer>> subsets = new ArrayList<>();
					List<Map.Entry<Blob, Integer>> list = new ArrayList<>(
							coresPerBlob.entrySet());
					ss(list, list.size(), new HashMap<>(), j, subsets, true);
					if (subsets.size() > 0) {
						Set<Blob> blobs = subsets.get(0).keySet();
						socketBlobAssignment(i, subsets.get(0), coresForBlob);
						for (Blob b : blobs)
							coresPerBlob.remove(b);
						break;
					}
				}
			}

			if (coresPerBlob.size() != 0)
				breakBlobs(coresPerBlob, coresForBlob);
			return coresForBlob;
		}

		private void reInializeFreeCoreMap() {
			for (int i = 0; i < Machine.sockets; i++)
				freeCoresMap.put(i, Machine.coresPerSocket);
		}

		private void breakBlobs(Map<Blob, Integer> coresPerBlob,
				Map<Blob, Set<Integer>> coresForBlob) {
			for (Map.Entry<Blob, Integer> en : coresPerBlob.entrySet()) {
				if (currentFreeCores() == 0)
					reInializeFreeCoreMap();
				for (int i = en.getValue();; i++) {
					if (i > 5000)
						System.out.println("dddddddddddddddddddd");
					List<Map<Integer, Integer>> subsets = new ArrayList<>();
					List<Map.Entry<Integer, Integer>> list = new ArrayList<>(
							freeCoresMap.entrySet());
					ss(list, list.size(), new HashMap<>(), i, subsets, false);
					if (subsets.size() > 0) {
						subsets.sort((m1, m2) -> Integer.compare(m1.size(),
								m2.size()));
						Map<Integer, Integer> m = subsets.get(0);
						int required = en.getValue();
						Set<Integer> s = new HashSet<Integer>();
						for (Map.Entry<Integer, Integer> e : m.entrySet()) {
							int a = Math.min(required, e.getValue());
							s.addAll(cores(e.getKey(), a));
							required -= a;
						}
						coresForBlob.put(en.getKey(), s);
						break;
					}
				}
			}
		}

		private int currentFreeCores() {
			int free = 0;
			for (int i : freeCoresMap.values())
				free += i;
			return free;
		}

		private int requiredCores(Map<Blob, Integer> coresPerBlob) {
			int req = 0;
			for (int i : coresPerBlob.values())
				req += i;
			return req;
		}

		private void socketBlobAssignment(int socket,
				Map<Blob, Integer> subset, Map<Blob, Set<Integer>> coresForBlob) {
			int totalJobs = 0;
			for (int i : subset.values())
				totalJobs += i;
			if (totalJobs > Machine.coresPerSocket)
				throw new IllegalStateException(
						String.format(
								"Total jobs(%d) assigned to the processor(%d) is greater than the available cores(%d)",
								totalJobs, socket, Machine.coresPerSocket));

			for (Map.Entry<Blob, Integer> en : subset.entrySet()) {
				coresForBlob.put(en.getKey(), cores(socket, en.getValue()));
			}
		}

		private Set<Integer> cores(int socket, int noOfCores) {
			int socketStart = socket * Machine.coresPerSocket;
			int freeCores = freeCoresMap.get(socket);
			if (noOfCores > freeCores)
				throw new IllegalStateException(String.format(
						"Socket(%d): noOfCores(%d) > availableFreeCores(%d)",
						socket, noOfCores, freeCores));
			Set<Integer> s = new HashSet<>();
			int freeCoreStart = Machine.coresPerSocket - freeCores;
			for (int i = 0; i < noOfCores; i++)
				s.add(socketStart + freeCoreStart++);
			freeCoresMap.replace(socket, freeCores - noOfCores);
			return s;
		}

		/**
		 * Finds subsetsums. The original code was copied from
		 * http://www.edufyme.com/code/?id=45c48cce2e2d7fbdea1afc51c7c6ad26.
		 * 
		 * I added an additional parameter (boolean firstSubSet) to stop
		 * exploring too many subsets. If the flag is true, it returns only the
		 * very first subset.
		 * 
		 */
		private static <T> boolean ss(List<Map.Entry<T, Integer>> list, int n,
				Map<T, Integer> subset, int sum, List<Map<T, Integer>> subsets,
				boolean firstSubSet) {

			if (sum == 0) {
				// printAns(subset);
				subsets.add(subset);
				return firstSubSet;
			}

			if (n == 0)
				return false;

			boolean ret;
			if (list.get(n - 1).getValue() <= sum) {
				ret = ss(list, n - 1, new HashMap<>(subset), sum, subsets,
						firstSubSet);
				if (ret)
					return true;
				Map.Entry<T, Integer> en = list.get(n - 1);
				subset.put(en.getKey(), en.getValue());
				ret = ss(list, n - 1, new HashMap<>(subset),
						sum - en.getValue(), subsets, firstSubSet);
				if (ret)
					return true;
			} else {
				ret = ss(list, n - 1, new HashMap<>(subset), sum, subsets,
						firstSubSet);
				if (ret)
					return true;
			}
			return false;
		}

		/**
		 * If hyper threading is enabled, Linux first enumerates all physical
		 * cores without considering the hyper threading and then enumerates the
		 * hyper threaded (virtual) cores. E.g, On Lanka05 (it has two sockets,
		 * 12 cores per socket, and hyper threading enabled), Linux lists the
		 * cores as follows
		 * <ol>
		 * <li>node0 CPU(s): 0-11,24-35
		 * <li>node1 CPU(s): 12-23,36-47
		 * </ol>
		 * 
		 * @param physicalCoreID
		 * @return
		 */
		private int getCorrespondingVirtualCoreID(int physicalCoreID) {
			if (physicalCoreID >= physicalCores)
				throw new IllegalArgumentException(String.format(
						"physicalCoreID(%d) >= Machine.physicalCores(%d)",
						physicalCoreID, physicalCores));
			if (Machine.isHTEnabled)
				return physicalCoreID + physicalCores;
			return physicalCoreID;
		}

		private static <T> void printAns(Map<T, Integer> subset) {
			for (Map.Entry i : subset.entrySet())
				System.out.print(i.getValue() + " ");
			System.out.println();
		}

		private static void printTable(
				ImmutableTable<Token, Integer, Integer> assignmentTable) {
			for (Map.Entry<Token, Map<Integer, Integer>> row : assignmentTable
					.rowMap().entrySet()) {
				Token t = row.getKey();
				System.out.print(t + " :-");
				for (Map.Entry<Integer, Integer> en : row.getValue().entrySet()) {
					System.out.print(String.format("%d-%d, ", en.getKey(),
							en.getValue()));
				}
				System.out.println();
			}
		}

		public void dumpAffinityTable(String appName, String namePrefix,
				long streamNodeID) {
			if (assignmentTable == null)
				return;
			AffinityTable at = new AffinityTable(assignmentTable);
			try {
				File dir = new File(String.format("%s%s%s", appName,
						File.separator, ConfigurationUtils.configDir));
				if (!dir.exists())
					if (!dir.mkdirs()) {
						System.err.println("Make directory failed");
						return;
					}
				File file = new File(dir, String.format("%s_%d.at", namePrefix,
						streamNodeID));
				FileOutputStream fout = new FileOutputStream(file, false);
				ObjectOutputStream oos = new ObjectOutputStream(fout);
				oos.writeObject(at);
				oos.close();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	/**
	 * @author sumanan
	 * @since 21 Dec, 2015
	 */
	public static class AllParallelAffinityManager implements AffinityManager {

		private final Set<Blob> blobSet;

		private final ImmutableTable<Token, Integer, Integer> assignmentTable;

		private final List<Processor> processorList;

		AllParallelAffinityManager(Set<Blob> blobSet) {
			this.blobSet = blobSet;
			this.processorList = processorList();
			assignmentTable = assign();
			CoreCodeAffinityManager.printTable(assignmentTable);
		}

		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			return ImmutableSet.of(assignmentTable.get(blobID, coreCode));
		}

		private ImmutableTable<Token, Integer, Integer> assign() {
			ImmutableTable.Builder<Token, Integer, Integer> tBuilder = ImmutableTable
					.builder();
			for (Blob b : blobSet) {
				Token t = Utils.getBlobID(b);
				Processor p = getMinProcessor();
				for (int i = 0; i < b.getCoreCount(); i++)
					tBuilder.put(t, i, p.newCore());
			}
			return tBuilder.build();
		}

		/**
		 * @return Processor where minimum number of cores are used.
		 */
		private Processor getMinProcessor() {
			Processor minProc = processorList.get(0);
			for (Processor p : processorList) {
				if (p.usedCores < minProc.usedCores)
					minProc = p;
			}
			return minProc;
		}

		private List<Processor> processorList() {
			List<Processor> pList = new LinkedList<>();
			int coreIDStartPoint;
			int virtualCoreIDStartPoint = -10000; // virtualCoreIDStartPoint of
													// a machine. Just a
													// negative value.
			if (Machine.isHTEnabled)
				virtualCoreIDStartPoint = Machine.sockets
						* Machine.coresPerSocket;
			int vCoreIDStartPoint;// virtualCoreIDStartPoint for a particular
									// processor.
			for (int pID = 0; pID < Machine.sockets; pID++) {
				coreIDStartPoint = pID * Machine.coresPerSocket;
				vCoreIDStartPoint = virtualCoreIDStartPoint + coreIDStartPoint;
				Processor p = new Processor(pID, Machine.coresPerSocket,
						Machine.isHTEnabled, coreIDStartPoint,
						vCoreIDStartPoint);
				pList.add(p);
			}
			return pList;
		}
	}

	/**
	 * @author sumanan
	 * @since 15 Dec, 2015
	 */
	public static class FileAffinityManager implements AffinityManager {

		private final ImmutableTable<Token, Integer, Integer> assignmentTable;

		FileAffinityManager(String appName, String namePrefix, long streamNodeID) {
			String atFilePath = String.format("%s%s%s%s%s_%d.at", appName,
					File.separator, ConfigurationUtils.configDir,
					File.separator, namePrefix, streamNodeID);
			AffinityTable at = null;
			try {
				FileInputStream fin = new FileInputStream(atFilePath);
				ObjectInputStream ois = new ObjectInputStream(fin);
				at = (AffinityTable) ois.readObject();
				ois.close();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			assignmentTable = at.assignmentTable;
			CoreCodeAffinityManager.printTable(assignmentTable);
		}

		@Override
		public ImmutableSet<Integer> getAffinity(Token blobID, int coreCode) {
			return ImmutableSet.of(assignmentTable.get(blobID, coreCode));
		}
	}

	public static class AffinityTable implements Serializable {
		private static final long serialVersionUID = 1L;
		private transient ImmutableTable<Token, Integer, Integer> assignmentTable;

		public AffinityTable(
				ImmutableTable<Token, Integer, Integer> assignmentTable) {
			this.assignmentTable = assignmentTable;
		}

		private void writeObject(ObjectOutputStream oos) throws IOException {
			oos.defaultWriteObject();
			oos.writeObject(assignmentTable.rowMap());
		}

		private void readObject(ObjectInputStream ois) throws IOException,
				ClassNotFoundException {
			ois.defaultReadObject();
			ImmutableMap<Token, Map<Integer, Integer>> map = (ImmutableMap<Token, Map<Integer, Integer>>) ois
					.readObject();
			ImmutableTable.Builder<Token, Integer, Integer> builder = ImmutableTable
					.builder();
			for (Map.Entry<Token, Map<Integer, Integer>> e1 : map.entrySet())
				for (Map.Entry<Integer, Integer> e2 : e1.getValue().entrySet())
					builder.put(e1.getKey(), e2.getKey(), e2.getValue());
			assignmentTable = builder.build();
		}
	}

	/**
	 * Represents a processor.
	 * 
	 * @author sumanan
	 * @since 21 Dec, 2015
	 */
	private static class Processor implements Comparable<Processor> {

		final int id;

		final int physicalCores;

		final boolean isHTEnabled;

		final int coreIDStartPoint;

		final int virtualCoreIDStartPoint;

		final int totalCores;

		int usedCores = 0;

		int curCore = 0;

		Processor(int id, int physicalCores, boolean isHTEnabled,
				int coreIDStartPoint, int virtualCoreIDStartPoint) {
			this.id = id;
			this.physicalCores = physicalCores;
			this.isHTEnabled = isHTEnabled;
			this.coreIDStartPoint = coreIDStartPoint;
			this.virtualCoreIDStartPoint = virtualCoreIDStartPoint;
			totalCores = isHTEnabled ? 2 * physicalCores : physicalCores;
		}

		int newCore() {
			int c = curCore;
			int offset;
			if (c < physicalCores) {
				offset = coreIDStartPoint;
			} else {
				if (!isHTEnabled)
					throw new IllegalStateException(
							String.format(
									"No hyper threading, but c(%d) >= physicalCores(%d)",
									c, physicalCores));
				if (c >= 2 * physicalCores)
					throw new IllegalStateException(String.format(
							"c(%d) >= 2*physicalCores(%d)", c, physicalCores));
				c = c - physicalCores;
				offset = virtualCoreIDStartPoint;
			}
			incCurCore();
			return offset + c;
		}

		private void incCurCore() {
			usedCores++;
			curCore++;
			if (curCore == totalCores)
				curCore = 0;
		}

		@Override
		public int compareTo(Processor o) {
			return Integer.compare(usedCores, o.usedCores);
		}
	}
}
