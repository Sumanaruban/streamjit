package edu.mit.streamjit.impl.distributed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.AbstractReadOnlyBuffer;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.BlobFactory;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.common.AbstractDrainer.BlobGraph;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.MessageConstraint;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.distributed.common.GlobalConstants;
import edu.mit.streamjit.impl.distributed.common.Utils;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;
import edu.mit.streamjit.impl.distributed.runtimer.OnlineTuner;
import edu.mit.streamjit.impl.interp.Interpreter;

/**
 * This class contains all information about the current streamJit application
 * including {@link BlobGraph}, current {@link Configuration},
 * partitionsMachineMap1, and etc. Three main classes,
 * {@link DistributedStreamCompiler}, {@link Controller} and {@link OnlineTuner}
 * will be using this class of their functional purpose.
 * <p>
 * All member variables of this class are public, because this class is supposed
 * to be used by only trusted classes.
 * </p>
 * 
 * @author Sumanan sumanan@mit.edu
 * @since Oct 8, 2013
 */
public class StreamJitApp {

	/**
	 * Since this is final, lets make public
	 */
	public final String topLevelClass;

	public final Worker<?, ?> source;

	public final Worker<?, ?> sink;

	public final String jarFilePath;

	public final String name;

	final OneToOneElement<?, ?> streamGraph;

	public BlobGraph blobGraph;

	public Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap;

	public ImmutableMap<Token, Buffer> bufferMap;

	public List<MessageConstraint> constraints;

	public DrainData drainData = null;

	public final Visualizer visualizer;

	/**
	 * Keeps track of assigned machine Ids of each blob. This information is
	 * need for draining. TODO: If possible use a better solution.
	 */
	public Map<Token, Integer> blobtoMachineMap;

	/**
	 * The latest valid {@link Configuration} that is received from OpenTuner.
	 * {@link BlobFactory#getDefaultConfiguration(java.util.Set) generates the
	 * initial configuration}.
	 */
	private Configuration configuration = null;

	public StreamJitApp(OneToOneElement<?, ?> streamGraph, Worker<?, ?> source,
			Worker<?, ?> sink) {
		this.streamGraph = streamGraph;
		this.name = streamGraph.getClass().getSimpleName();
		this.topLevelClass = streamGraph.getClass().getName();
		this.source = source;
		this.sink = sink;
		this.jarFilePath = this.getClass().getProtectionDomain()
				.getCodeSource().getLocation().getPath();
		Utils.createAppDir(name);
		visualizer = new Visualizer.DotVisualizer(streamGraph);
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph} and
	 * {@link StreamJitApp#partitionsMachineMap} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @return true iff no cycles among blobs
	 */
	public boolean newPartitionMap(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		try {
			verifyConfiguration(partitionsMachineMap);
		} catch (StreamCompilationFailedException ex) {
			return false;
		}
		return true;
	}

	/**
	 * Builds {@link BlobGraph} from the partitionsMachineMap, and verifies for
	 * any cycles among blobs. If it is a valid partitionsMachineMap, (i.e., no
	 * cycles among the blobs), then this objects member variables
	 * {@link StreamJitApp#blobGraph} and
	 * {@link StreamJitApp#partitionsMachineMap} will be assigned according to
	 * the new configuration, no changes otherwise.
	 * 
	 * @param partitionsMachineMap
	 * 
	 * @throws StreamCompilationFailedException
	 *             if any cycles found among blobs.
	 */
	public void verifyConfiguration(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {

		if (!GlobalConstants.singleNodeOnline) {
			// printPartition(partitionsMachineMap);
		}

		List<Set<Worker<?, ?>>> partitionList = new ArrayList<>();
		for (List<Set<Worker<?, ?>>> lst : partitionsMachineMap.values()) {
			partitionList.addAll(lst);
		}

		BlobGraph bg = null;
		try {
			bg = new BlobGraph(partitionList);
		} catch (StreamCompilationFailedException ex) {
			System.err.println("Cycles found in the worker->blob assignment");
			printPartition(partitionsMachineMap);
			throw ex;
		}
		this.blobGraph = bg;
		this.partitionsMachineMap = partitionsMachineMap;
	}

	private void printPartition(
			Map<Integer, List<Set<Worker<?, ?>>>> partitionsMachineMap) {
		for (int machine : partitionsMachineMap.keySet()) {
			System.err.print("\nMachine - " + machine);
			for (Set<Worker<?, ?>> blobworkers : partitionsMachineMap
					.get(machine)) {
				System.err.print("\n\tBlob worker set : ");
				for (Worker<?, ?> w : blobworkers) {
					System.err.print(Workers.getIdentifier(w) + " ");
				}
			}
		}
		System.err.println();
	}

	/**
	 * From aggregated drain data, get subset of it which is relevant to a
	 * particular machine. Builds and returns machineID to DrainData map.
	 * 
	 * @return Drain data mapped to machines.
	 */
	public ImmutableMap<Integer, DrainData> getDrainData() {
		ImmutableMap.Builder<Integer, DrainData> builder = ImmutableMap
				.builder();

		if (this.drainData != null) {
			for (Integer machineID : partitionsMachineMap.keySet()) {
				List<Set<Worker<?, ?>>> blobList = partitionsMachineMap
						.get(machineID);
				DrainData dd = drainData.subset(getWorkerIds(blobList));
				builder.put(machineID, dd);
			}
		}
		return builder.build();
	}

	/**
	 * Uses an {@link Interpreter} blob to clear or minimize a {@link DrainData}
	 * . This method can be called after a final draining to clear the data in
	 * the intermediate buffers.
	 * 
	 * @param drainData
	 *            : {@link DrainData} that is received after a draining.
	 * @return : A {@link DrainData} that remains after running an
	 *         {@link Interpreter} blob.
	 */
	public DrainData minimizeDrainData(DrainData drainData) {
		Interpreter.InterpreterBlobFactory interpFactory = new Interpreter.InterpreterBlobFactory();
		Blob interp = interpFactory.makeBlob(Workers
				.getAllWorkersInGraph(source), interpFactory
				.getDefaultConfiguration(Workers.getAllWorkersInGraph(source)),
				1, drainData);
		interp.installBuffers(bufferMapWithEmptyHead());
		Runnable interpCode = interp.getCoreCode(0);
		final AtomicBoolean interpFinished = new AtomicBoolean();
		interp.drain(new Runnable() {
			@Override
			public void run() {
				interpFinished.set(true);
			}
		});
		while (!interpFinished.get())
			interpCode.run();
		return interp.getDrainData();
	}

	/**
	 * Remove the original headbuffer and replace it with a new empty buffer.
	 */
	private ImmutableMap<Token, Buffer> bufferMapWithEmptyHead() {
		ImmutableMap.Builder<Token, Buffer> bufMapBuilder = ImmutableMap
				.builder();
		Buffer head = new AbstractReadOnlyBuffer() {
			@Override
			public int size() {
				return 0;
			}

			@Override
			public Object read() {
				return null;
			}
		};

		Token headToken = Token.createOverallInputToken(source);
		for (Map.Entry<Token, Buffer> en : bufferMap.entrySet()) {
			if (en.getKey().equals(headToken))
				bufMapBuilder.put(headToken, head);
			else
				bufMapBuilder.put(en);
		}
		return bufMapBuilder.build();
	}

	private Set<Integer> getWorkerIds(List<Set<Worker<?, ?>>> blobList) {
		Set<Integer> workerIds = new HashSet<>();
		for (Set<Worker<?, ?>> blobworkers : blobList) {
			for (Worker<?, ?> w : blobworkers) {
				workerIds.add(Workers.getIdentifier(w));
			}
		}
		return workerIds;
	}

	/**
	 * @return the configuration
	 */
	public Configuration getConfiguration() {
		return configuration;
	}

	/**
	 * @param configuration
	 *            the configuration to set
	 */
	public void setConfiguration(Configuration configuration) {
		this.configuration = configuration;
		visualizer.newConfiguration(configuration);
		visualizer.newPartitionMachineMap(partitionsMachineMap);
	}
}
