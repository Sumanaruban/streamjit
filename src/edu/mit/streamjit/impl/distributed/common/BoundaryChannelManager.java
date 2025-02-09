package edu.mit.streamjit.impl.distributed.common;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.common.drainer.AbstractDrainer.DrainDataAction;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel.BoundaryInputChannel;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel.BoundaryOutputChannel;
import edu.mit.streamjit.impl.distributed.node.AsyncOutputChannel;

/**
 * Manages set of {@link BoundaryChannel}s.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 28, 2014
 */
public interface BoundaryChannelManager {

	void start();

	void waitToStart();

	void waitToStop();

	public interface BoundaryInputChannelManager extends BoundaryChannelManager {

		/**
		 * In streamJit, a channel can be identified by a {@link Token}.
		 * 
		 * @return map of channel {@link Token}, {@link BoundaryInputChannel}
		 *         handled by this manager.
		 */
		ImmutableMap<Token, BoundaryInputChannel> inputChannelsMap();

		/**
		 * @param drainDataAction
		 *            See {@link BoundaryInputChannel#stop(int)}
		 */
		void stop(DrainDataAction drainDataAction);
	}

	public interface BoundaryOutputChannelManager extends
			BoundaryChannelManager {

		/**
		 * In streamJit, a channel can be identified by a {@link Token}.
		 * 
		 * @return map of channel {@link Token}, {@link BoundaryOutputChannel}
		 *         handled by this manager.
		 */
		ImmutableMap<Token, BoundaryOutputChannel> outputChannelsMap();

		/**
		 * @param stopType
		 *            See {@link BoundaryOutputChannel#stop(boolean)}
		 */
		void stop(boolean stopType);
	}

	public static class InputChannelManager implements
			BoundaryInputChannelManager {

		private final ImmutableMap<Token, BoundaryInputChannel> inputChannels;

		private final Set<Thread> inputChannelThreads;

		private boolean isStarted;

		public InputChannelManager(
				final ImmutableMap<Token, BoundaryInputChannel> inputChannels) {
			this.inputChannels = inputChannels;
			inputChannelThreads = new HashSet<>(inputChannels.values().size());
			isStarted = false;
		}

		@Override
		public void start() {
			if (isStarted)
				throw new IllegalStateException(
						"inputChannels have already been started");
			for (BoundaryInputChannel bc : inputChannels.values()) {
				Thread t = new Thread(bc.getRunnable(), bc.name());
				t.start();
				inputChannelThreads.add(t);
			}
			isStarted = true;
		}

		@Override
		public void waitToStart() {
		}

		@Override
		public void waitToStop() {
			for (Thread t : inputChannelThreads) {
				try {
					t.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public void stop(DrainDataAction drainDataAction) {
			for (BoundaryInputChannel bc : inputChannels.values()) {
				bc.stop(drainDataAction);
			}
		}

		@Override
		public ImmutableMap<Token, BoundaryInputChannel> inputChannelsMap() {
			return inputChannels;
		}
	}

	public static class OutputChannelManager implements
			BoundaryOutputChannelManager {

		protected final ImmutableMap<Token, BoundaryOutputChannel> outputChannels;
		protected final Map<BoundaryOutputChannel, Thread> outputChannelThreads;

		private boolean isStarted;

		public OutputChannelManager(
				ImmutableMap<Token, BoundaryOutputChannel> outputChannels) {
			this.outputChannels = outputChannels;
			outputChannelThreads = new HashMap<>(outputChannels.values().size());
			isStarted = false;
		}

		@Override
		public void start() {
			if (isStarted)
				throw new IllegalStateException(
						"outputChannels have already been started");
			for (BoundaryOutputChannel bc : outputChannels.values()) {
				Thread t = new Thread(bc.getRunnable(), bc.name());
				t.start();
				outputChannelThreads.put(bc, t);
			}
			isStarted = true;
		}

		@Override
		public void waitToStart() {
			for (Map.Entry<BoundaryOutputChannel, Thread> en : outputChannelThreads
					.entrySet()) {
				if (en.getKey() instanceof AsyncOutputChannel) {
					try {
						en.getValue().join();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}

		@Override
		public void stop(boolean stopType) {
			for (BoundaryOutputChannel bc : outputChannels.values()) {
				bc.stop(stopType);
			}
		}

		@Override
		public void waitToStop() {
			for (Thread t : outputChannelThreads.values()) {
				try {
					t.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		public ImmutableMap<Token, BoundaryOutputChannel> outputChannelsMap() {
			return outputChannels;
		}
	}
}
