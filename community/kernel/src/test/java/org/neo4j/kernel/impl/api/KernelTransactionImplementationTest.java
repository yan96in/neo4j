/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.neo4j.graphdb.TransactionTerminatedException;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.kernel.api.security.AccessMode;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.NoOpClient;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.storageengine.api.StorageCommand;
import org.neo4j.storageengine.api.StorageStatement;
import org.neo4j.storageengine.api.lock.ResourceLocker;
import org.neo4j.test.DoubleLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith( Parameterized.class )
public class KernelTransactionImplementationTest extends KernelTransactionTestBase
{
    @Parameterized.Parameter( 0 )
    public Consumer<KernelTransaction> transactionInitializer;

    @Parameterized.Parameter( 1 )
    public boolean isWriteTx;

    @Parameterized.Parameter( 2 )
    public String ignored; // to make JUnit happy...

    @Parameterized.Parameters( name = "{2}" )
    public static Collection<Object[]> parameters()
    {
        Consumer<KernelTransaction> readTxInitializer = tx -> {
        };
        Consumer<KernelTransaction> writeTxInitializer = tx -> {
            KernelStatement statement = (KernelStatement) tx.acquireStatement();
            statement.txState().nodeDoCreate( 42 );
        };
        return Arrays.asList(
                new Object[]{readTxInitializer, false, "read"},
                new Object[]{writeTxInitializer, true, "write"}
        );
    }

    @Test
    public void shouldCommitSuccessfulTransaction() throws Exception
    {
        // GIVEN
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
            transaction.success();
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( true, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    private AccessMode accessMode()
    {
        return isWriteTx ? AccessMode.Static.WRITE : AccessMode.Static.READ;
    }

    @Test
    public void shouldRollbackUnsuccessfulTransaction() throws Exception
    {
        // GIVEN
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldRollbackFailedTransaction() throws Exception
    {
        // GIVEN
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
            transaction.failure();
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldRollbackAndThrowOnFailedAndSuccess() throws Exception
    {
        // GIVEN
        boolean exceptionReceived = false;
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
            transaction.failure();
            transaction.success();
        }
        catch ( TransactionFailureException e )
        {
            // Expected.
            exceptionReceived = true;
        }

        // THEN
        assertTrue( exceptionReceived );
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldRollbackOnClosingTerminatedTransaction() throws Exception
    {
        // GIVEN
        KernelTransaction transaction = newTransaction( accessMode() );

        transactionInitializer.accept( transaction );
        transaction.success();
        transaction.markForTermination();

        try
        {
            // WHEN
            transaction.close();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionTerminatedException.class ) );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldRollbackOnClosingSuccessfulButTerminatedTransaction() throws Exception
    {
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
            transaction.markForTermination();
            assertTrue( transaction.shouldBeTerminated() );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldRollbackOnClosingTerminatedButSuccessfulTransaction() throws Exception
    {
        // GIVEN
        KernelTransaction transaction = newTransaction( accessMode() );

        transactionInitializer.accept( transaction );
        transaction.markForTermination();
        transaction.success();
        assertTrue( transaction.shouldBeTerminated() );

        try
        {
            // WHEN
            transaction.close();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionTerminatedException.class ) );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldNotDowngradeFailureState() throws Exception
    {
        try ( KernelTransaction transaction = newTransaction( accessMode() ) )
        {
            // WHEN
            transactionInitializer.accept( transaction );
            transaction.markForTermination();
            transaction.failure();
            assertTrue( transaction.shouldBeTerminated() );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldIgnoreTerminateAfterCommit() throws Exception
    {
        KernelTransaction transaction = newTransaction( accessMode() );
        transactionInitializer.accept( transaction );
        transaction.success();
        transaction.close();
        transaction.markForTermination();

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( true, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldIgnoreTerminateAfterRollback() throws Exception
    {
        KernelTransaction transaction = newTransaction( accessMode() );
        transactionInitializer.accept( transaction );
        transaction.close();
        transaction.markForTermination();

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test( expected = TransactionTerminatedException.class )
    public void shouldThrowOnTerminationInCommit() throws Exception
    {
        KernelTransaction transaction = newTransaction( accessMode() );
        transactionInitializer.accept( transaction );
        transaction.success();
        transaction.markForTermination();
        transaction.close();
    }

    @Test
    public void shouldIgnoreTerminationDuringRollback() throws Exception
    {
        KernelTransaction transaction = newTransaction( accessMode() );
        transactionInitializer.accept( transaction );
        transaction.markForTermination();
        transaction.close();

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    class ChildException
    {
        public Exception exception = null;
    }

    @Test
    public void shouldAllowTerminatingFromADifferentThread() throws Exception
    {
        // GIVEN
        final ChildException childException = new ChildException();
        final DoubleLatch latch = new DoubleLatch( 1 );
        final KernelTransaction transaction = newTransaction( accessMode() );
        transactionInitializer.accept( transaction );

        Future<?> terminationFuture = Executors.newSingleThreadExecutor().submit( () -> {
            latch.awaitStart();
            transaction.markForTermination();
            latch.finish();
        } );

        // WHEN
        transaction.success();
        latch.startAndAwaitFinish();

        assertNull( terminationFuture.get( 1, TimeUnit.MINUTES ) );

        try
        {
            transaction.close();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionTerminatedException.class ) );
        }

        // THEN
        verify( transactionMonitor, times( 1 ) ).transactionFinished( false, isWriteTx );
        verify( transactionMonitor, times( 1 ) ).transactionTerminated( isWriteTx );
        verifyExtraInteractionWithTheMonitor( transactionMonitor, isWriteTx );
    }

    @Test
    public void shouldUseStartTimeAndTxIdFromWhenStartingTxAsHeader() throws Exception
    {
        // GIVEN a transaction starting at one point in time
        long startingTime = clock.currentTimeMillis();
        when( legacyIndexState.hasChanges() ).thenReturn( true );
        doAnswer( invocation -> {
            Collection<StorageCommand> commands = invocation.getArgumentAt( 0, Collection.class );
            commands.add( mock( Command.class ) );
            return null;
        } ).when( storageEngine ).createCommands(
                any( Collection.class ),
                any( TransactionState.class ),
                any( StorageStatement.class ),
                any( ResourceLocker.class ),
                anyLong() );

        try ( KernelTransactionImplementation transaction = newTransaction( accessMode() ) )
        {
            transaction.initialize( 5L, mock( Locks.Client.class ), KernelTransaction.Type.implicit,
                    AccessMode.Static.FULL );
            try ( KernelStatement statement = transaction.acquireStatement() )
            {
                statement.legacyIndexTxState(); // which will pull it from the supplier and the mocking above
                // will have it say that it has changes.
            }
            // WHEN committing it at a later point
            clock.forward( 5, MILLISECONDS );
            // ...and simulating some other transaction being committed
            when( metaDataStore.getLastCommittedTransactionId() ).thenReturn( 7L );
            transaction.success();
        }

        // THEN start time and last tx when started should have been taken from when the transaction started
        assertEquals( 5L, commitProcess.transaction.getLatestCommittedTxWhenStarted() );
        assertEquals( startingTime, commitProcess.transaction.getTimeStarted() );
        assertEquals( startingTime + 5, commitProcess.transaction.getTimeCommitted() );
    }

    @Test
    public void successfulTxShouldNotifyKernelTransactionsThatItIsClosed() throws TransactionFailureException
    {
        KernelTransactionImplementation tx = newTransaction( accessMode() );

        tx.success();
        tx.close();

        verify( txPool ).release( tx );
    }

    @Test
    public void failedTxShouldNotifyKernelTransactionsThatItIsClosed() throws TransactionFailureException
    {
        KernelTransactionImplementation tx = newTransaction( accessMode() );

        tx.failure();
        tx.close();

        verify( txPool ).release( tx );
    }

    private void verifyExtraInteractionWithTheMonitor( TransactionMonitor transactionMonitor, boolean isWriteTx )
    {
        if ( isWriteTx )
        {
            verify( this.transactionMonitor, times( 1 ) ).upgradeToWriteTransaction();
        }
        verifyNoMoreInteractions( transactionMonitor );
    }

    @Test
    public void shouldIncrementReuseCounterOnReuse() throws Exception
    {
        // GIVEN
        KernelTransactionImplementation transaction = newTransaction( accessMode() );
        int reuseCount = transaction.getReuseCount();

        // WHEN
        transaction.close();
        transaction.initialize( 1, new NoOpClient(), KernelTransaction.Type.implicit, accessMode() );

        // THEN
        assertEquals( reuseCount + 1, transaction.getReuseCount() );
    }

    @Test
    public void markForTerminationNotInitializedTransaction()
    {
        KernelTransactionImplementation tx = newNotInitializedTransaction( true );

        tx.markForTermination();

        assertTrue( tx.shouldBeTerminated() );
    }

    @Test
    public void markForTerminationInitializedTransaction()
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );

        tx.markForTermination();

        assertTrue( tx.shouldBeTerminated() );
        verify( locksClient ).stop();
    }

    @Test
    public void markForTerminationTerminatedTransaction()
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        transactionInitializer.accept( tx );

        tx.markForTermination();
        tx.markForTermination();
        tx.markForTermination();

        assertTrue( tx.shouldBeTerminated() );
        verify( locksClient ).stop();
        verify( transactionMonitor ).transactionTerminated( isWriteTx );
    }

    @Test
    public void terminatedTxMarkedNeitherSuccessNorFailureClosesWithoutThrowing() throws TransactionFailureException
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        transactionInitializer.accept( tx );
        tx.markForTermination();

        tx.close();

        verify( locksClient ).stop();
        verify( transactionMonitor ).transactionTerminated( isWriteTx );
    }

    @Test
    public void terminatedTxMarkedForSuccessThrowsOnClose()
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        transactionInitializer.accept( tx );
        tx.success();
        tx.markForTermination();

        try
        {
            tx.close();
            fail( "Exception expected" );
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionTerminatedException.class ) );
        }
    }

    @Test
    public void terminatedTxMarkedForFailureClosesWithoutThrowing() throws TransactionFailureException
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        transactionInitializer.accept( tx );
        tx.failure();
        tx.markForTermination();

        tx.close();

        verify( locksClient ).stop();
        verify( transactionMonitor ).transactionTerminated( isWriteTx );
    }

    @Test
    public void terminatedTxMarkedForBothSuccessAndFailureThrowsOnClose()
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        transactionInitializer.accept( tx );
        tx.success();
        tx.failure();
        tx.markForTermination();

        try
        {
            tx.close();
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionTerminatedException.class ) );
        }
    }

    @Test
    public void txMarkedForBothSuccessAndFailureThrowsOnClose()
    {
        Locks.Client locksClient = mock( Locks.Client.class );
        KernelTransactionImplementation tx = newTransaction( accessMode(), locksClient, true );
        tx.success();
        tx.failure();

        try
        {
            tx.close();
        }
        catch ( Exception e )
        {
            assertThat( e, instanceOf( TransactionFailureException.class ) );
        }
    }
}
