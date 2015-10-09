package edu.mit.streamjit.impl.distributed.node;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.Buffers;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.distributed.common.AppStatus;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel.BoundaryInputChannel;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel.BoundaryOutputChannel;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannelManager.BoundaryInputChannelManager;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannelManager.BoundaryOutputChannelManager;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannelManager.InputChannelManager;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannelManager.OutputChannelManager;
import edu.mit.streamjit.impl.distributed.common.CTRLRDrainElement.DrainType;
import edu.mit.streamjit.impl.distributed.common.CompilationInfo.InitScheduleCompleted;
import edu.mit.streamjit.impl.distributed.common.Connection;
import edu.mit.streamjit.impl.distributed.common.SNMessageElement;
import edu.mit.streamjit.impl.distributed.common.SNMessageElement.SNMessageElementHolder;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;
import edu.mit.streamjit.tuner.EventTimeLogger;
import edu.mit.streamjit.util.affinity.Affinity;

/**
 * This class was an inner class of {@link BlobsManagerImpl}. I have re factored
 * {@link BlobsManagerImpl} and moved this class a new file.
 * 
 * @author sumanan
 * @since 4 Feb, 2015
 */
class BlobExecuter {

	/**
	 * 
	 */
	final BlobsManagerImpl blobsManagerImpl;

	Blob blob;

	final Token blobID;

	final Set<BlobThread2> blobThreads;

	/**
	 * Buffers for all input and output edges of the {@link #blob}.
	 */
	ImmutableMap<Token, Buffer> bufferMap;

	ImmutableMap<Token, LocalBuffer> outputLocalBuffers;

	/**
	 * This flag will be set to true if an exception thrown by the core code of
	 * the {@link Blob}. Any exception occurred in a blob's corecode will be
	 * informed to {@link Controller} to halt the application. See the
	 * {@link BlobThread2}.
	 */
	AtomicBoolean crashed;

	final BoundaryInputChannelManager inChnlManager;

	final BoundaryOutputChannelManager outChnlManager;

	final Starter starter;

	final EventTimeLogger eventTimeLogger;

	final BlobDrainer drainer;

	BlobExecuter(BlobsManagerImpl blobsManagerImpl, Token t, Blob blob,
			ImmutableMap<Token, BoundaryInputChannel> inputChannels,
			ImmutableMap<Token, BoundaryOutputChannel> outputChannels) {
		this.blobsManagerImpl = blobsManagerImpl;
		this.eventTimeLogger = blobsManagerImpl.streamNode.eventTimeLogger;
		this.crashed = new AtomicBoolean(false);
		this.blob = blob;
		this.blobThreads = new HashSet<>();
		assert blob.getInputs().containsAll(inputChannels.keySet());
		assert blob.getOutputs().containsAll(outputChannels.keySet());
		this.inChnlManager = new InputChannelManager(inputChannels);
		this.outChnlManager = new OutputChannelManager(outputChannels);

		String baseName = getName(blob);
		for (int i = 0; i < blob.getCoreCount(); i++) {
			String name = String.format("%s - %d", baseName, i);
			blobThreads.add(new BlobThread2(blob.getCoreCode(i), this, name,
					blobsManagerImpl.affinityManager.getAffinity(blob, i),
					i == 0));
		}

		if (blobThreads.size() < 1)
			throw new IllegalStateException("No blobs to execute");

		this.blobID = t;
		this.starter = new StatelessStarter();
		this.drainer = new BlobDrainer(this);
	}

	public Token getBlobID() {
		return blobID;
	}

	/**
	 * Gets buffer from {@link BoundaryChannel}s and builds bufferMap. The
	 * bufferMap will contain all input and output edges of the {@link #blob}.
	 * 
	 * Note that, Some {@link BoundaryChannel}s (e.g.,
	 * {@link AsyncOutputChannel}) create {@link Buffer}s after establishing
	 * {@link Connection} with other end. So this method must be called after
	 * establishing all IO connections.
	 * {@link InputChannelManager#waitToStart()} and
	 * {@link OutputChannelManager#waitToStart()} ensure that the IO connections
	 * are successfully established.
	 * 
	 * @return Buffer map which contains {@link Buffers} for all input and
	 *         output edges of the {@link #blob}.
	 */
	private ImmutableMap<Token, Buffer> buildBufferMap() {
		ImmutableMap.Builder<Token, Buffer> bufferMapBuilder = ImmutableMap
				.builder();
		ImmutableMap.Builder<Token, LocalBuffer> outputLocalBufferBuilder = ImmutableMap
				.builder();
		ImmutableMap<Token, LocalBuffer> localBufferMap = this.blobsManagerImpl.bufferManager
				.localBufferMap();
		ImmutableMap<Token, BoundaryInputChannel> inputChannels = inChnlManager
				.inputChannelsMap();
		ImmutableMap<Token, BoundaryOutputChannel> outputChannels = outChnlManager
				.outputChannelsMap();

		for (Token t : blob.getInputs()) {
			if (localBufferMap.containsKey(t)) {
				assert !inputChannels.containsKey(t) : "Same channels is exists in both localBuffer and inputChannel";
				bufferMapBuilder.put(t, localBufferMap.get(t));
			} else if (inputChannels.containsKey(t)) {
				BoundaryInputChannel chnl = inputChannels.get(t);
				bufferMapBuilder.put(t, chnl.getBuffer());
			} else {
				throw new AssertionError(String.format(
						"No Buffer for input channel %s ", t));
			}
		}

		for (Token t : blob.getOutputs()) {
			if (localBufferMap.containsKey(t)) {
				assert !outputChannels.containsKey(t) : "Same channels is exists in both localBuffer and outputChannel";
				LocalBuffer buf = localBufferMap.get(t);
				bufferMapBuilder.put(t, buf);
				outputLocalBufferBuilder.put(t, buf);
			} else if (outputChannels.containsKey(t)) {
				BoundaryOutputChannel chnl = outputChannels.get(t);
				bufferMapBuilder.put(t, chnl.getBuffer());
			} else {
				throw new AssertionError(String.format(
						"No Buffer for output channel %s ", t));
			}
		}
		outputLocalBuffers = outputLocalBufferBuilder.build();
		return bufferMapBuilder.build();
	}

	/**
	 * Returns a name for thread.
	 * 
	 * @param blob
	 * @return
	 */
	private String getName(Blob blob) {
		StringBuilder sb = new StringBuilder("Workers-");
		int limit = 0;
		for (Worker<?, ?> w : blob.getWorkers()) {
			sb.append(Workers.getIdentifier(w));
			sb.append(",");
			if (++limit > 5)
				break;
		}
		return sb.toString();
	}

	void startChannels() {
		outChnlManager.start();
		inChnlManager.start();
	}

	void stop() {
		inChnlManager.stop(DrainType.FINAL);
		outChnlManager.stop(true);

		for (Thread t : blobThreads) {
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		bEvent("inChnlManager.waitToStop");
		inChnlManager.waitToStop();
		eEvent("inChnlManager.waitToStop");
		bEvent("outChnlManager.waitToStop");
		outChnlManager.waitToStop();
		eEvent("outChnlManager.waitToStop");

		if (this.blobsManagerImpl.monBufs != null)
			this.blobsManagerImpl.monBufs.stopMonitoring();
		if (drainer.executorService != null
				&& !drainer.executorService.isTerminated())
			drainer.executorService.shutdownNow();
	}

	void bEvent(String eventName) {
		eventTimeLogger.bEvent(blobID + eventName);
	}

	long eEvent(String eventName) {
		return eventTimeLogger.eEvent(blobID + eventName);
	}

	void logEvent(String eventName, long elapsedMills) {
		eventTimeLogger.logEvent(blobID + eventName, elapsedMills);
	}

	final class BlobThread2 extends Thread {

		private final Set<Integer> cores;

		private final BlobExecuter be;

		private final Runnable coreCode;

		private volatile boolean stopping = false;

		private final boolean logTime;

		BlobThread2(Runnable coreCode, BlobExecuter be, String name,
				Set<Integer> cores, boolean logTime) {
			super(name);
			this.coreCode = coreCode;
			this.be = be;
			this.cores = cores;
			this.logTime = logTime;
		}

		public void requestStop() {
			stopping = true;
		}

		@Override
		public void run() {
			if (cores != null && cores.size() > 0)
				Affinity.setThreadAffinity(cores);
			try {
				starter.initScheduleRun(this);
				if (logTime)
					logFiringTime();
				while (!stopping) {
					coreCode.run();
				}
			} catch (Error | Exception e) {
				System.out.println(Thread.currentThread().getName()
						+ " crashed...");
				if (be.crashed.compareAndSet(false, true)) {
					e.printStackTrace();
					if (drainer.drainState == 1 || drainer.drainState == 2)
						drainer.drained();
					else if (drainer.drainState == 0) {
						try {
							blobsManagerImpl.streamNode.controllerConnection
									.writeObject(new SNMessageElementHolder(
											AppStatus.ERROR,
											be.blobsManagerImpl.appInstId));
						} catch (IOException e1) {
							e1.printStackTrace();
						}
					}
				}
			}
		}

		private void logFiringTime() {
			int meassureCount = 5;
			// The very first coreCode.run() executes initCode which is single
			// threaded and very much slower than steadyCode. With initCode,
			// lets skip another few steadyCode executions before begin the
			// measurement.
			for (int i = 0; i < 10; i++) {
				if (stopping)
					break;
				coreCode.run();
			}

			Stopwatch sw = Stopwatch.createStarted();
			for (int i = 0; i < meassureCount; i++) {
				if (stopping)
					break;
				coreCode.run();
			}
			if (!stopping) {
				long time = sw.elapsed(TimeUnit.MILLISECONDS);
				long avgMills = time / meassureCount;
				logEvent("-firing", avgMills);
			}
		}
	}

	/**
	 * In order to achieve seamless reconfiguration we need to start stateless
	 * and stateful graphs differently. If the graph is stateless, we can run
	 * the next app instance's initSchedule while the current app instance is
	 * running and then join the outputs. If the graph is stateful, we need to
	 * stop the current app instance before running the next app instance.
	 * 
	 * <p>
	 * Alternatively, We can get rid of this interface, move the methods of
	 * interface to {@link BlobExecuter} as abstract methods, and have two
	 * different flavors of {@link BlobExecuter} implementations, one for
	 * stateful graph and another for stateless graph.
	 * 
	 * @author sumanan
	 * @since 8 Oct, 2015
	 */
	interface Starter {
		void start();
		void runInitSchedule(int steadyRunCount);
		void initScheduleRun(BlobThread2 bt) throws InterruptedException,
				IOException;
		void startChannels();
	}

	/**
	 * {@link Starter} for stateless graphs.
	 * 
	 * @author sumanan
	 * @since 8 Oct, 2015
	 */
	private final class StatelessStarter implements Starter {

		volatile int steadyRunCount;
		private final Object initScheduleRunMonitor = new Object();
		private boolean isChannelsStarted = false;

		@Override
		public void start() {
			synchronized (initScheduleRunMonitor) {
				initScheduleRunMonitor.notifyAll();
			}
		}

		@Override
		public void runInitSchedule(int steadyRunCount) {
			outChnlManager.waitToStart();
			inChnlManager.waitToStart();

			bufferMap = buildBufferMap();
			blob.installBuffers(bufferMap);

			for (BlobThread2 t : blobThreads) {
				this.steadyRunCount = steadyRunCount;
				t.start();
			}
			// System.out.println(blobID + " started");
		}

		@Override
		public void initScheduleRun(BlobThread2 bt)
				throws InterruptedException, IOException {
			if (bt.logTime)
				bEvent("initScheduleRun");
			for (int i = 0; i < steadyRunCount + 1; i++) {
				if (bt.stopping)
					break;
				bt.coreCode.run();
			}
			if (bt.logTime) {
				long time = eEvent("initScheduleRun");
				SNMessageElement me = new InitScheduleCompleted(blobID, time);
				blobsManagerImpl.streamNode.controllerConnection
						.writeObject(me);
			}

			synchronized (initScheduleRunMonitor) {
				initScheduleRunMonitor.wait();
			}
		}

		@Override
		public void startChannels() {
			if (!isChannelsStarted) {
				BlobExecuter.this.startChannels();
				isChannelsStarted = true;
			}
		}
	}

	/**
	 * {@link Starter} for stateful graphs.
	 * 
	 * @author sumanan
	 * @since 8 Oct, 2015
	 */
	private final class StatefullStarter implements Starter {

		@Override
		public void start() {
			outChnlManager.waitToStart();
			inChnlManager.waitToStart();

			bufferMap = buildBufferMap();
			blob.installBuffers(bufferMap);

			for (Thread t : blobThreads)
				t.start();

			// System.out.println(blobID + " started");
		}

		@Override
		public void runInitSchedule(int steadyRunCount) {
			throw new IllegalStateException(
					"Can not run InitSchedule in advance for stateful graphs.");
		}

		@Override
		public void initScheduleRun(BlobThread2 bt)
				throws InterruptedException, IOException {
		}

		@Override
		public void startChannels() {
			BlobExecuter.this.startChannels();
		}
	}
}
