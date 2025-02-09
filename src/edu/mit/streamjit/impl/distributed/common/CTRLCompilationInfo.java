package edu.mit.streamjit.impl.distributed.common;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.DrainData;

public abstract class CTRLCompilationInfo implements CTRLRMessageElement {

	private static final long serialVersionUID = 1L;

	public abstract void process(CTRLCompilationInfoProcessor cip);

	@Override
	public void accept(CTRLRMessageVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * Buffer sizes
	 */
	public static final class FinalBufferSizes extends CTRLCompilationInfo {
		private static final long serialVersionUID = 1L;
		public final ImmutableMap<Token, Integer> minInputBufCapacity;

		public FinalBufferSizes(
				final ImmutableMap<Token, Integer> minInputBufCapacity) {
			this.minInputBufCapacity = minInputBufCapacity;
		}

		@Override
		public void process(CTRLCompilationInfoProcessor cip) {
			cip.process(this);
		}
	}

	public static final class InitSchedule extends CTRLCompilationInfo {

		private static final long serialVersionUID = 1L;
		public final ImmutableMap<Token, Integer> steadyRunCount;

		public InitSchedule(ImmutableMap<Token, Integer> steadyRunCount) {
			this.steadyRunCount = steadyRunCount;
		}

		@Override
		public void process(CTRLCompilationInfoProcessor cip) {
			cip.process(this);
		}
	}

	public static final class DDSizes extends CTRLCompilationInfo {

		private static final long serialVersionUID = 1L;

		@Override
		public void process(CTRLCompilationInfoProcessor cip) {
			cip.process(this);
		}
	}

	/**
	 * @author sumanan
	 * @since 16 Nov, 2015
	 */
	public static final class RequestState extends CTRLCompilationInfo {

		private static final long serialVersionUID = 1L;

		/**
		 * Requests a blob to send its state at #sendStateAt'th firing
		 * (adjustCount).
		 */
		public final int sendStateAt;

		public final Token blobID;

		public RequestState(Token blobID, int sendStateAt) {
			this.blobID = blobID;
			this.sendStateAt = sendStateAt;
		}

		@Override
		public void process(CTRLCompilationInfoProcessor cip) {
			cip.process(this);
		}
	}

	public interface CTRLCompilationInfoProcessor {
		public void process(FinalBufferSizes finalBufferSizes);
		public void process(InitSchedule InitSchedule);
		public void process(InitialState cfgDD);
		public void process(DDSizes ddSizes);
		public void process(RequestState requestState);
	}

	/**
	 * ConfigurationString2-DrainData
	 * 
	 * @author sumanan
	 * @since 27 Aug, 2015
	 */
	public static final class InitialState extends CTRLCompilationInfo {
		private static final long serialVersionUID = 1L;
		public final DrainData drainData;

		public InitialState(DrainData drainData) {
			this.drainData = drainData;
		}

		@Override
		public void process(CTRLCompilationInfoProcessor cip) {
			cip.process(this);
		}
	}
}
