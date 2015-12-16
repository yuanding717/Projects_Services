/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.test.backend.lucene;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.hibernate.search.backend.impl.CommitPolicy;
import org.hibernate.search.backend.impl.lucene.AbstractWorkspaceImpl;
import org.hibernate.search.backend.impl.lucene.ScheduledCommitPolicy;
import org.hibernate.search.backend.spi.Work;
import org.hibernate.search.backend.spi.WorkType;
import org.hibernate.search.engine.integration.impl.ExtendedSearchIntegrator;
import org.hibernate.search.query.engine.spi.HSQuery;
import org.hibernate.search.testsupport.junit.SearchFactoryHolder;
import org.hibernate.search.testsupport.setup.CountingErrorHandler;
import org.hibernate.search.testsupport.setup.TransactionContextForTest;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.hibernate.search.test.backend.lucene.Conditions.assertConditionMet;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the scheduled commit policy
 *
 * @author gustavonalle
 */
@RunWith(BMUnitRunner.class)
public class ScheduledCommitPolicyTest {

	private static final int NUMBER_ENTITIES = 1000;

	private int globalIdCounter = 0;

	@Rule
	public SearchFactoryHolder sfAsyncExclusiveIndex = new SearchFactoryHolder( Quote.class )
			.withProperty( "hibernate.search.default.index_flush_interval", "1" )
			.withProperty( "hibernate.search.default.worker.execution", "async" )
			.withProperty( "hibernate.search.default.exclusive_index_use", "true" )
			.withProperty( "hibernate.search.error_handler", CountingErrorHandler.class.getName() );

	@Test
	public void testScheduledCommits() throws Exception {
		writeData( sfAsyncExclusiveIndex, NUMBER_ENTITIES );
		AbstractWorkspaceImpl workspace = sfAsyncExclusiveIndex.extractWorkspace( Quote.class );
		CommitPolicy commitPolicy = workspace.getCommitPolicy();

		assertTrue( commitPolicy instanceof ScheduledCommitPolicy );

		ScheduledCommitPolicy scheduledCommitPolicy = (ScheduledCommitPolicy) commitPolicy;
		ScheduledThreadPoolExecutor scheduledExecutor = (ScheduledThreadPoolExecutor) scheduledCommitPolicy.getScheduledExecutorService();

		assertConditionMet( new TaskExecutedCondition( scheduledExecutor, 1 ) );
	}

	@Test
	@BMRule(targetClass = "org.apache.lucene.index.IndexWriter",
			targetMethod = "commit",
			action = "throw new IOException(\"File not found!\")",
			name = "commitError")
	public void testErrorHandlingDuringCommit() throws Exception {
		writeData( sfAsyncExclusiveIndex, 2 );
		final CountingErrorHandler errorHandler = (CountingErrorHandler) sfAsyncExclusiveIndex.getSearchFactory().getErrorHandler();
		assertConditionMet( new Condition() {
			@Override
			public boolean evaluate() {
				return errorHandler.getCountFor( IOException.class ) >= 2;
			}
		} );
	}

	@Test
	@BMRule(targetClass = "org.hibernate.search.backend.impl.lucene.IndexWriterHolder",
			targetMethod = "commitIndexWriter()",
			action = "throw new NullPointerException(\"Fake internal error\")",
			name = "timerDisruptingError")
	public void testErrorHandlingOnBackgroundThread() throws Exception {
		writeData( sfAsyncExclusiveIndex, 2 );
		final CountingErrorHandler errorHandler = (CountingErrorHandler) sfAsyncExclusiveIndex.getSearchFactory().getErrorHandler();
		assertConditionMet( new Condition() {
			@Override
			public boolean evaluate() {
				// It's going to commit once each millisecond, and produce a failure each time.
				// So "4" is just a random number higher than 0, but high enough to
				// verify that the scheduled task is not being killed at the first failure,
				// and will keep trying.
				return errorHandler.getCountFor( NullPointerException.class ) >= 4;
			}
		} );
	}

	@Test
	public void testDocVisibility() throws Exception {
		writeData( sfAsyncExclusiveIndex, NUMBER_ENTITIES );
		assertConditionMet( new IndexingFinishedCondition( sfAsyncExclusiveIndex, NUMBER_ENTITIES ) );

		writeData( sfAsyncExclusiveIndex, 10 );
		assertConditionMet( new IndexingFinishedCondition( sfAsyncExclusiveIndex, NUMBER_ENTITIES + 10 ) );

		writeData( sfAsyncExclusiveIndex, 1 );
		assertConditionMet( new IndexingFinishedCondition( sfAsyncExclusiveIndex, NUMBER_ENTITIES + 10 + 1 ) );
	}

	private class IndexingFinishedCondition implements Condition {
		private final int expectedDocsCount;
		private final ExtendedSearchIntegrator searchFactory;
		private IndexingFinishedCondition(SearchFactoryHolder searchFactoryHolder, int expectedDocsCount) {
			this.searchFactory = searchFactoryHolder.getSearchFactory();
			this.expectedDocsCount = expectedDocsCount;
		}

		private HSQuery matchAllQuery() {
			return searchFactory
					.createHSQuery()
					.luceneQuery( new MatchAllDocsQuery() )
					.targetedEntities( Arrays.<Class<?>>asList( Quote.class ) );
		}

		@Override
		public boolean evaluate() {
			return expectedDocsCount == matchAllQuery().queryResultSize();
		}
	}

	private class TaskExecutedCondition implements Condition {

		private final ScheduledThreadPoolExecutor executor;
		private final int taskCount;

		private TaskExecutedCondition(ScheduledThreadPoolExecutor executor, int taskCount) {
			this.executor = executor;
			this.taskCount = taskCount;
		}
		@Override
		public boolean evaluate() {
			return executor.getCompletedTaskCount() >= taskCount;
		}
	}

	private void writeData(SearchFactoryHolder sfHolder, int numberEntities) {
		for ( int i = 0; i < numberEntities; i++ ) {
			Integer id = Integer.valueOf( globalIdCounter++ );
			Quote quote = new Quote( id, "description" );
			Work work = new Work( quote, id, WorkType.ADD, false );
			TransactionContextForTest tc = new TransactionContextForTest();
			sfHolder.getSearchFactory().getWorker().performWork( work, tc );
			tc.end();
		}
	}

}
