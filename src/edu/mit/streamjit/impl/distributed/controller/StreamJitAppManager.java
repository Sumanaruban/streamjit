/*
 * Copyright (c) 2013-2014 Massachusetts Institute of Technology
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package edu.mit.streamjit.impl.distributed.controller;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.api.CompiledStream;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.Configuration.IntParameter;
import edu.mit.streamjit.impl.distributed.common.AppStatus;
import edu.mit.streamjit.impl.distributed.common.CTRLRMessageElement.CTRLRMessageElementHolder;
import edu.mit.streamjit.impl.distributed.common.ConfigurationString;
import edu.mit.streamjit.impl.distributed.common.ConfigurationString.ConfigurationProcessor.ConfigType;
import edu.mit.streamjit.impl.distributed.common.ConfigurationString.ConfigurationString1;
import edu.mit.streamjit.impl.distributed.common.Connection.ConnectionInfo;
import edu.mit.streamjit.impl.distributed.common.Error.ErrorProcessor;
import edu.mit.streamjit.impl.distributed.common.GlobalConstants;
import edu.mit.streamjit.impl.distributed.common.Options;
import edu.mit.streamjit.impl.distributed.common.SNTimeInfo.SNTimeInfoProcessor;
import edu.mit.streamjit.impl.distributed.common.SNTimeInfoProcessorImpl;
import edu.mit.streamjit.impl.distributed.controller.SeamlessReconfigurer.SeamlessStatefulReconfigurer;
import edu.mit.streamjit.impl.distributed.controller.SeamlessReconfigurer.SeamlessStatelessReconfigurer;
import edu.mit.streamjit.impl.distributed.profiler.MasterProfiler;
import edu.mit.streamjit.impl.distributed.profiler.ProfilerCommand;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;

/**
 * @author Sumanan sumanan@mit.edu
 * @since Oct 30, 2013
 */
public class StreamJitAppManager {

	final StreamJitApp<?, ?> app;

	final ConnectionManager conManager;

	final Controller controller;

	private final ErrorProcessor ep;

	private final SNTimeInfoProcessor timeInfoProcessor;

	final MasterProfiler profiler;

	public final AppDrainer appDrainer;

	private volatile AppStatus status;

	volatile AppInstanceManager prevAIM = null;
	volatile AppInstanceManager curAIM = null;

	final int noOfnodes;

	public final Reconfigurer reconfigurer;

	private final Map<Integer, AppInstanceManager> AIMs = new HashMap<>();

	public StreamJitAppManager(Controller controller, StreamJitApp<?, ?> app,
			ConnectionManager conManager) {
		noOfnodes = controller.getAllNodeIDs().size();
		this.controller = controller;
		this.app = app;
		this.conManager = conManager;
		this.timeInfoProcessor = new SNTimeInfoProcessorImpl(app.logger);
		this.status = AppStatus.NOT_STARTED;
		this.ep = new ErrorProcessorImpl();

		appDrainer = new AppDrainer();
		this.reconfigurer = reconfigurer(app.tailBuffer);
		setNewApp(); // TODO: Makes IO communication. Find a good calling place.
		profiler = setupProfiler();
	}

	public ErrorProcessor errorProcessor() {
		return ep;
	}

	public SNTimeInfoProcessor timeInfoProcessor() {
		return timeInfoProcessor;
	}

	private void setNewApp() {
		controller.registerManager(this);
		Configuration.Builder builder = app.getStaticConfiguration();
		builder.addParameter(new IntParameter(GlobalConstants.StarterType, 1,
				2, reconfigurer.starterType()));
		controller.newApp(builder);
	}

	Reconfigurer reconfigurer(Buffer tailBuffer) {
		boolean adaptiveReconfig = Options.Reconfigurer == 2 ? true : false;
		boolean stateful = Options.useDrainData ? app.stateful : false;

		switch (Options.Reconfigurer) {
			case 0 :
				return new PauseResumeReconfigurer(this);
			default : {
				if (stateful)
					return new SeamlessStatefulReconfigurer(this, tailBuffer,
							adaptiveReconfig);
				else
					return new SeamlessStatelessReconfigurer(this, tailBuffer,
							adaptiveReconfig);
			}
		}
	}

	public AppInstanceManager getAppInstManager(int appInstId) {
		AppInstanceManager aim = AIMs.get(appInstId);
		if (aim == null)
			throw new IllegalStateException(String.format(
					"No AppInstanceManager with ID=%d exists", appInstId));
		return aim;
	}

	public MasterProfiler getProfiler() {
		return profiler;
	}

	public AppStatus getStatus() {
		return status;
	}

	public boolean isRunning() {
		return curAIM.isRunning;
	}

	AppInstanceManager createNewAIM(AppInstance appinst) {
		if (prevAIM != null && prevAIM.isRunning)
			throw new IllegalStateException(
					"Couldn't create a new AIM as already two AppInstances are running. Drain the current AppInstance first.");

		prevAIM = curAIM;
		curAIM = new AppInstanceManager(appinst, this);
		AIMs.put(curAIM.appInstId(), curAIM);
		return curAIM;
	}

	void removeAIM(int appInstId) {
		AppInstanceManager aim = AIMs.remove(appInstId);
		aim.releaseAllResources();
		System.out.println(String.format(
				"AIM-%d has been removed. Total live AIMs are %d", appInstId,
				AIMs.size()));
	}

	// TODO:seamless.
	/*
	 * public void setDrainer(AbstractDrainer drainer) { assert dp == null :
	 * "SNDrainProcessor has already been set"; this.dp = new
	 * SNDrainProcessorImpl(drainer); }
	 */

	public void stop() {
		this.status = AppStatus.STOPPED;
		curAIM.headTailHandler.tailChannel.reset();
		controller.closeAll();
		// dp.drainer.stop();
		appDrainer.stop();
		reconfigurer.stop();
		System.out.println(String.format("%s: Stopped.", app.name));
	}

	public long getFixedOutputTime(long timeout) throws InterruptedException {
		if (prevAIM != null)
			prevAIM.waitToStop();
		long time = curAIM.headTailHandler.tailChannel
				.getFixedOutputTime(timeout);
		if (curAIM.apStsPro.error) {
			return -1l;
		}
		return time;
	}

	public void drainingFinished(boolean isFinal, AppInstanceManager aim) {
		reconfigurer.drainingFinished(isFinal, aim);
		if (isFinal)
			stop();
	}

	void reset() {
		// 2015-10-26.
		// As exP is moved to AppInstManager, we don't need to reset it.
		// exP.exConInfos = new HashSet<>();
		// No need to do the following resets as we create new appInstManager at
		// every reconfiguration.
		// appInstManager.apStsPro.reset();
		// appInstManager.ciP.reset();
	}

	private MasterProfiler setupProfiler() {
		MasterProfiler p = null;
		if (Options.needProfiler) {
			p = new MasterProfiler(app.name);
			controller.sendToAll(new CTRLRMessageElementHolder(
					ProfilerCommand.START, -1));
		}
		return p;
	}

	/**
	 * Performs the steps that need to be done in order to create new blobs at
	 * stream nodes side. Specifically, sends new configuration along with drain
	 * data to stream nodes for compilation.
	 * 
	 * @param appinst
	 */
	void preCompilation(AppInstanceManager currentAim,
			AppInstanceManager previousAim) {
		String jsonStirng = currentAim.dynamicCfg(connectionsInUse());
		ImmutableMap<Integer, DrainData> drainDataMap;
		if (previousAim == null)
			drainDataMap = ImmutableMap.of();
		else
			drainDataMap = previousAim.appInst.getDrainData();
		System.out.println("drainDataMap.size() = " + drainDataMap.size());
		app.logger.compilationStarted();
		currentAim.eLogger.bEvent("compilation");
		for (int nodeID : controller.getAllNodeIDs()) {
			ConfigurationString json = new ConfigurationString1(jsonStirng,
					ConfigType.DYNAMIC, drainDataMap.get(nodeID));
			controller.send(nodeID, new CTRLRMessageElementHolder(json,
					currentAim.appInst.id));
		}
	}

	void preCompilation(AppInstanceManager currentAim,
			ImmutableMap<Token, Integer> drainDataSize) {
		String jsonStirng = currentAim.dynamicCfg(connectionsInUse());
		app.logger.compilationStarted();
		currentAim.eLogger.bEvent("compilation");
		for (int nodeID : controller.getAllNodeIDs()) {
			ConfigurationString json = new ConfigurationString.ConfigurationString2(
					jsonStirng, ConfigType.DYNAMIC, drainDataSize);
			controller.send(nodeID, new CTRLRMessageElementHolder(json,
					currentAim.appInst.id));
		}
	}

	private Collection<ConnectionInfo> connectionsInUse() {
		Collection<ConnectionInfo> connectionsInUse = null;
		if (prevAIM != null && prevAIM.conInfoMap != null)
			connectionsInUse = prevAIM.conInfoMap.values();
		return connectionsInUse;
	}

	/**
	 * {@link ErrorProcessor} at {@link Controller} side.
	 * 
	 * @author Sumanan sumanan@mit.edu
	 * @since Aug 11, 2013
	 */
	private class ErrorProcessorImpl implements ErrorProcessor {

		@Override
		public void processFILE_NOT_FOUND() {
			System.err
					.println("No application jar file in streamNode. Terminating...");
			stop();
		}

		@Override
		public void processWORKER_NOT_FOUND() {
			System.err
					.println("No top level class in the jar file. Terminating...");
			stop();
		}
	}

	public class AppDrainer {

		/**
		 * Latch to block the external thread that calls
		 * {@link CompiledStream#awaitDrained()}.
		 */
		private final CountDownLatch finalLatch;

		private AppDrainer() {
			finalLatch = new CountDownLatch(1);
		}

		/**
		 * @return true iff draining of the stream application is finished. See
		 *         {@link CompiledStream#isDrained()} for more details.
		 */
		public final boolean isDrained() {
			return finalLatch.getCount() == 0;
		}

		/**
		 * See {@link CompiledStream#awaitDrained()} for more details.
		 */
		public final void awaitDrained() throws InterruptedException {
			finalLatch.await();
		}

		/**
		 * See {@link CompiledStream#awaitDrained(long, TimeUnit)} for more
		 * details.
		 */
		public final void awaitDrained(long timeout, TimeUnit unit)
				throws InterruptedException, TimeoutException {
			finalLatch.await(timeout, unit);
		}

		/**
		 * In any case, if the application could not be executed (may be due to
		 * {@link Error}), {@link StreamCompiler} or appropriate class can call
		 * this method to release the main thread.
		 */
		public void stop() {
			// TODO: seamless
			// assert state != DrainerState.INTERMEDIATE :
			// "DrainerState.NODRAINING or DrainerState.FINAL is expected.";
			this.finalLatch.countDown();
		}

		public boolean drainFinal(Boolean isFinal) {
			// TODO: seamless
			// Need to drain newAIM also. drainer.drainFinal() method is
			// blocking. Need to to make this unblocking.
			return curAIM.drainer.drainFinal(isFinal);
		}
	}

	public interface Reconfigurer {
		/**
		 * @param appinst
		 * @return <ol>
		 *         <li>-0: Reconfiguration is successful.
		 *         <li>-1: Intermediate draining has failed.
		 *         <li>-2: Compilation has failed.
		 */
		public int reconfigure(AppInstance appinst);

		/**
		 * Type of starter required at StreamNode side.
		 * 
		 * @return
		 */
		public int starterType();

		public void drainingFinished(boolean isFinal, AppInstanceManager aim);

		public void stop();
	}
}
