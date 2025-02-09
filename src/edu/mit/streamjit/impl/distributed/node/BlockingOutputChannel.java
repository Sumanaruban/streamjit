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
package edu.mit.streamjit.impl.distributed.node;

import java.io.FileWriter;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.collect.ImmutableList;

import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.ConcurrentArrayBuffer;
import edu.mit.streamjit.impl.distributed.common.BoundaryChannel.BoundaryOutputChannel;
import edu.mit.streamjit.impl.distributed.common.Connection;
import edu.mit.streamjit.impl.distributed.common.Connection.ConnectionInfo;
import edu.mit.streamjit.impl.distributed.common.Connection.ConnectionProvider;

/**
 * This is {@link BoundaryOutputChannel} over TCP. Reads data from the given
 * {@link Buffer} and send them over the TCP connection.
 * <p>
 * Note: TCPOutputChannel acts as server when making TCP connection.
 * </p>
 * <p>
 * TODO: Need to aggressively optimise this class.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 29, 2013
 */
public class BlockingOutputChannel implements BoundaryOutputChannel {

	private final FileWriter writer;

	private final int debugLevel;

	private final Buffer buffer;

	private final ConnectionProvider conProvider;

	private final ConnectionInfo conInfo;

	private Connection connection;

	private final AtomicBoolean stopFlag;

	private final String name;

	private volatile boolean isFinal;

	private int count;

	protected ImmutableList<Object> unProcessedData;

	public BlockingOutputChannel(int bufSize, ConnectionProvider conProvider,
			ConnectionInfo conInfo, String bufferTokenName, int debugLevel) {
		this(new ConcurrentArrayBuffer(bufSize), conProvider, conInfo,
				bufferTokenName, debugLevel);
	}

	public BlockingOutputChannel(Buffer buffer, ConnectionProvider conProvider,
			ConnectionInfo conInfo, String bufferTokenName, int debugLevel) {
		this.buffer = buffer;
		this.conProvider = conProvider;
		this.conInfo = conInfo;
		this.stopFlag = new AtomicBoolean(false);
		this.isFinal = false;
		this.name = "BlockingOutputChnl-" + bufferTokenName;
		this.debugLevel = debugLevel;
		this.unProcessedData = null;
		count = 0;
		writer = fileWriter();
	}

	private FileWriter fileWriter() {
		FileWriter w = null;
		if (this.debugLevel == 5) {
			try {
				w = new FileWriter(name, true);
				w.write("---------------------------------\n");
			} catch (IOException e) {
				w = null;
				e.printStackTrace();
			}
		}
		return w;
	}

	private void closeConnection() {
		try {
			// tcpConnection.closeConnection();
			connection.softClose();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Runnable getRunnable() {
		return new Runnable() {
			@Override
			public void run() {
				makeConnection();

				while (!stopFlag.get())
					sendData();

				if (isFinal)
					finalSend();

				closeConnection();

				fillUnprocessedData();
				if (debugLevel > 0)
					debugMethod2();
			}
		};
	}

	public final void sendData() {
		while (this.buffer.size() > 0 && !stopFlag.get()) {
			send();
		}
	}

	/**
	 * This can be called when running the application with the final scheduling
	 * configurations. Shouldn't be called when autotuner tunes.
	 */
	private void finalSend() {
		while (this.buffer.size() > 0) {
			send();
		}
	}

	private void makeConnection() {
		if (connection == null || !connection.isStillConnected()) {
			try {
				connection = conProvider.getConnection(conInfo);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public final void stop(boolean isFinal) {
		if (debugLevel > 0)
			System.out.println(name + " : stop request");
		if (!this.stopFlag.get()) {
			this.isFinal = isFinal;
			this.stopFlag.set(true);
		} else if (debugLevel > 0) {
			System.err.println("Stop has already been called.");
		}
	}

	private void reConnect() {
		try {
			this.connection.closeConnection();
			while (!stopFlag.get()) {
				System.out.println("TCPOutputChannel : Reconnecting...");
				try {
					this.connection = conProvider.getConnection(conInfo, 1000);
					return;
				} catch (SocketTimeoutException stex) {
					// We make this exception to recheck the stopFlag. Otherwise
					// thread will get struck at server.accept().
				}
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	private void send() {
		try {
			Object o = buffer.read();
			connection.writeObject(o);

			if (debugLevel > 0)
				debugMethod1(o);

		} catch (IOException e) {
			e.printStackTrace();
			System.err.println("TCP Output Channel. Send exception.");
			reConnect();
		}
		if (debugLevel == 2 && count % 1000 == 0)
			System.out.println(name + " : " + count
					+ " no of items have been sent");
	}

	private void debugMethod1(Object o) throws IOException {
		if (debugLevel == 2)
			count++;

		if (debugLevel == 3) {
			System.out.println(name + " : Send : " + o.toString());
		}

		if (writer != null) {
			writer.write(o.toString());
			writer.write('\n');
		}
	}

	private void debugMethod2() {
		if (writer != null) {
			try {
				writer.flush();
				writer.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		if (debugLevel > 0) {
			System.err.println(name + " : Exiting...");
			System.out.println("isFinal " + isFinal);
			System.out.println("stopFlag " + stopFlag.get());
		}
	}

	@Override
	public final String name() {
		return name;
	}

	// TODO: Huge data copying is happening in this code twice. Need to optimise
	// this.
	protected void fillUnprocessedData() {
		int size = buffer.size();
		int arrayIdx = 0;
		Object[] obArray = new Object[size];

		for (int i = 0; i < size; i++) {
			Object o = buffer.read();
			if (o != null)
				obArray[arrayIdx++] = o;
			else
				System.err
						.println(String
								.format("fillUnprocessedData: Null object read at index %d.",
										i));
		}
		if (buffer.size() != 0) {
			throw new IllegalStateException(String.format(
					"buffer size is %d. But 0 is expected", buffer.size()));
		}
		this.unProcessedData = ImmutableList.copyOf(Arrays.copyOfRange(obArray,
				0, arrayIdx));
	}

	@Override
	public ImmutableList<Object> getUnprocessedData() {
		if (unProcessedData == null)
			throw new IllegalAccessError(
					"Still processing... No unprocessed data");
		return unProcessedData;
	}

	@Override
	public Connection getConnection() {
		return connection;
	}

	@Override
	public ConnectionInfo getConnectionInfo() {
		return conInfo;
	}

	@Override
	public Buffer getBuffer() {
		return buffer;
	}
}
