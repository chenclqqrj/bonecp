/**
 *  Copyright 2010 Wallace Wadge
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */


package com.jolbox.bonecp;

import java.sql.SQLException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically checks for connections to see if the connection has expired.
 * @author wwadge
 *
 */
public class ConnectionMaxAgeThread implements Runnable {

	/** Max no of ms to wait before a connection that isn't used is killed off. */
	private long maxAge;
	/** Partition being handled. */
	private ConnectionPartition partition;
	/** Scheduler handle. **/
	private ScheduledExecutorService scheduler;
	/** Handle to connection pool. */
	private BoneCP pool;
	/** Logger handle. */
	private static Logger logger = LoggerFactory.getLogger(ConnectionTesterThread.class);

	/** Constructor
	 * @param connectionPartition partition to work on
	 * @param scheduler Scheduler handler.
	 * @param pool pool handle
	 * @param maxAge Threads older than this are killed off 
	 */
	protected ConnectionMaxAgeThread(ConnectionPartition connectionPartition, ScheduledExecutorService scheduler, 
			BoneCP pool, long maxAge){
		this.partition = connectionPartition;
		this.scheduler = scheduler;
		this.maxAge = maxAge;
		this.pool = pool;
	}


	/** Invoked periodically. */
	public void run() {
		ConnectionHandle connection = null;
		long tmp;
		try {
				long nextCheck = this.maxAge;
				
				int partitionSize= this.partition.getAvailableConnections();
				long currentTime = System.currentTimeMillis();
				for (int i=0; i < partitionSize; i++){

					connection = this.partition.getFreeConnections().poll();
					if (connection != null){
						connection.setOriginatingPartition(this.partition);
						if (connection.isExpired(currentTime)){
							// kill off this connection
							closeConnection(connection);
							continue;
						}

						
						tmp = this.maxAge - (currentTime - connection.getConnectionCreationTime()); 
						
						if (tmp < nextCheck){
							nextCheck = tmp; 
						}
						
						this.pool.putConnectionBackInPartition(connection);

						Thread.sleep(20L); // test slowly, this is not an operation that we're in a hurry to deal with...
					}

				} // throw it back on the queue

				this.scheduler.schedule(this, nextCheck, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			if (this.scheduler.isShutdown()){
				logger.debug("Shutting down connection tester thread.");
			} else {
				logger.error("Connection tester thread interrupted", e);
			}
		}
	}


	/** Closes off this connection
	 * @param connection to close
	 */
	private void closeConnection(ConnectionHandle connection) {
		if (connection != null) {
			try {
				connection.internalClose();
				this.pool.postDestroyConnection(connection);

			} catch (SQLException e) {
				logger.error("Destroy connection exception", e);
			}
		}
	}
}
