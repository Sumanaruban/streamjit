package edu.mit.streamjit.impl.distributed.common;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

/**
 * Communication interface for an IO connection that is already created, i.e.,
 * creating a connections is not handled at here. Consider
 * {@link ConnectionFactory} to create a connection. </p> For the moment,
 * communicates at object granularity level. We may need to add primitive
 * interface functions later.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 14, 2013
 */
public interface Connection {

	/**
	 * Read an object from this connection.
	 * 
	 * @return Received object.
	 * @throws IOException
	 * @throws ClassNotFoundException
	 *             If the object received is not the type of T.
	 */
	public <T> T readObject() throws IOException, ClassNotFoundException;

	/**
	 * Write a object to the connection. </p>throws exception if failed. So no
	 * return value needed.
	 * 
	 * @throws IOException
	 */
	public void writeObject(Object obj) throws IOException;

	/**
	 * Close the connection. This function is responsible for all kind of
	 * resource cleanup. </p>throws exception if failed. So no return value
	 * needed.
	 * 
	 * @throws IOException
	 */
	public void closeConnection() throws IOException;

	/**
	 * Do not close the underlying real connection. Instead inform other side to
	 * the current communication session is over.
	 * <p>
	 * This is introduced because {@link ObjectInputStream#readObject()} eats
	 * thread InterruptedException and yields no way to close the reader thread
	 * when the thread is blocked at {@link ObjectInputStream#readObject()}
	 * method call.
	 * </p>
	 * 
	 * @throws IOException
	 */
	public void softClose() throws IOException;

	/**
	 * Checks whether the connection is still open or not.
	 * 
	 * @return true if the connection is open and valid.
	 */
	public boolean isStillConnected();

	/**
	 * Describes a connection between two machines. ConnectionInfo is considered
	 * symmetric for equal() and hashCode() calculation. As long as same
	 * machineIDs are involved, irrespect of srcID and dstID positions, these
	 * methods return same result.
	 * 
	 * <p>
	 * <b>Note : </b> All instances of ConnectionInfo, including subclass
	 * instances, will be equal to each other if the IDs matches. See the
	 * hashCode() and equals() methods. <b>The whole point of this class is to
	 * identify a connection between two machines.</b>
	 */
	public class ConnectionInfo implements Serializable {

		private static final long serialVersionUID = 1L;

		private final int srcID;

		private final int dstID;

		public ConnectionInfo(int srcID, int dstID) {
			this.srcID = srcID;
			this.dstID = dstID;
		}

		public int getSrcID() {
			return srcID;
		}

		public int getDstID() {
			return dstID;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			/*
			 * int min = Math.min(srcID, dstID); int max = Math.max(srcID,
			 * dstID); result = prime * result + min; result = prime * result +
			 * max;
			 */
			result = prime * result + srcID;
			result = prime * result + dstID;
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (!(obj instanceof ConnectionInfo))
				return false;
			ConnectionInfo other = (ConnectionInfo) obj;
			/*
			 * int myMin = Math.min(srcID, dstID); int myMax = Math.max(srcID,
			 * dstID); int otherMin = Math.min(other.srcID, other.dstID); int
			 * otherMax = Math.max(other.srcID, other.dstID); if (myMin !=
			 * otherMin) return false; if (myMax != otherMax) return false;
			 */
			if (srcID != other.srcID)
				return false;
			if (dstID != other.dstID)
				return false;
			return true;
		}

		@Override
		public String toString() {
			return "ConnectionInfo [srcID=" + srcID + ", dstID=" + dstID + "]";
		}
	}
}
