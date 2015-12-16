/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.impl.lucene;

import org.hibernate.search.exception.ErrorHandler;
import org.hibernate.search.util.impl.Executors;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Commit policy that will commit at a regular intervals defined by configuration or immediately on
 * explicit flush requests
 *
 * @author gustavonalle
 */
public final class ScheduledCommitPolicy extends AbstractCommitPolicy {

	public static final int DEFAULT_DELAY_MS = 1000;

	private final ScheduledExecutorService scheduledExecutorService;
	private final ErrorHandler errorHandler;
	private final int delay;

	public ScheduledCommitPolicy(IndexWriterHolder indexWriterHolder, String indexName, int delay, ErrorHandler errorHandler) {
		super( indexWriterHolder );
		this.delay = delay;
		this.errorHandler = errorHandler;
		this.scheduledExecutorService = Executors.newScheduledThreadPool( "Commit Scheduler for index " + indexName );
		scheduledExecutorService.scheduleWithFixedDelay( new CommitTask(), 0, delay, TimeUnit.MILLISECONDS );
	}

	public int getDelay() {
		return delay;
	}

	@Override
	public void onChangeSetApplied(boolean someFailureHappened, boolean streaming) {
		if ( someFailureHappened ) {
			indexWriterHolder.forceLockRelease();
		}
	}

	public ScheduledExecutorService getScheduledExecutorService() {
		return scheduledExecutorService;
	}

	@Override
	public void onFlush() {
		indexWriterHolder.commitIndexWriter();
	}

	@Override
	public void onClose() {
		scheduledExecutorService.shutdown();
	}

	private final class CommitTask implements Runnable {

		@Override
		public void run() {
			// This is technically running in a race condition with a possible shutdown
			// (indexwriter getting closed), which would cause an AlreadyClosedException exception,
			// but gets swallowed as it's running in the service thread (which is also shutting down).
			try {
				indexWriterHolder.commitIndexWriter();
			}
			catch (Exception e) {
				errorHandler.handleException( "Error caught in background thread of ScheduledCommitPolicy", e );
			}
		}
	}

}
