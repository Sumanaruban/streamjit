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
package edu.mit.streamjit.impl.distributed.common;

import java.io.Serializable;

import edu.mit.streamjit.impl.distributed.controller.AppInstance;

public interface SNMessageElement extends Serializable {

	public void accept(SNMessageVisitor visitor);

	public static class SNMessageElementHolder implements Serializable {

		private static final long serialVersionUID = 1L;

		public final SNMessageElement me;

		public final int appInstId;

		/**
		 * Use -1 for appInstId to send non {@link AppInstance} related messages
		 * to controller. Controller also uses -1 to send non app related
		 * messages.
		 * 
		 * @param me
		 * @param appInstId
		 */
		public SNMessageElementHolder(SNMessageElement me, int appInstId) {
			this.appInstId = appInstId;
			this.me = me;
		}
	}
}
