/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.ha;

import org.neo4j.com.MasterUtil;
import org.neo4j.com.Response;
import org.neo4j.com.SlaveContext;
import org.neo4j.com.StoreWriter;
import org.neo4j.com.TxExtractor;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Pair;
import org.neo4j.helpers.Predicate;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.Config;
import org.neo4j.kernel.DeadlockDetectedException;
import org.neo4j.kernel.HaConfig;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.impl.core.LockReleaser;
import org.neo4j.kernel.impl.core.NodeManager;
import org.neo4j.kernel.impl.nioneo.store.IdGenerator;
import org.neo4j.kernel.impl.nioneo.store.StoreId;
import org.neo4j.kernel.impl.transaction.IllegalResourceException;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.LockType;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.util.StringLogger;

import javax.transaction.NotSupportedException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This is the real master code that executes on a master. The actual
 * communication over network happens in {@link MasterClient} and
 * {@link MasterServer}.
 */
public class MasterImpl implements Master
{
    private static final int ID_GRAB_SIZE = 1000;
    public static final int UNFINISHED_TRANSACTION_CLEANUP_DELAY = 5;

    private final GraphDatabaseService graphDb;
    private final StringLogger msgLog;

    private final Map<SlaveContext, Pair<Transaction, AtomicLong/*Time since last suspended*/>> transactions = Collections
            .synchronizedMap( new HashMap<SlaveContext, Pair<Transaction, AtomicLong>>() );
    private final ScheduledExecutorService unfinishedTransactionsExecutor;
    private int unfinishedTransactionThreshold;

    public MasterImpl( GraphDatabaseService db, Map<String, String> config )
    {
        this.graphDb = db;
        this.msgLog = ((AbstractGraphDatabase) db ).getMessageLog();
        this.unfinishedTransactionThreshold = HaConfig.getClientLockReadTimeoutFromConfig( config );
        this.unfinishedTransactionsExecutor = Executors.newSingleThreadScheduledExecutor();
        this.unfinishedTransactionsExecutor.scheduleWithFixedDelay( new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    Map<SlaveContext, Pair<Transaction, AtomicLong>> safeTransactions = null;
                    synchronized ( transactions )
                    {
                        safeTransactions = new HashMap<SlaveContext, Pair<Transaction,AtomicLong>>( transactions );
                    }

                    for ( Map.Entry<SlaveContext, Pair<Transaction, AtomicLong>> entry : safeTransactions.entrySet() )
                    {
                        long time = entry.getValue().other().get();
                        if ( time != 0 && System.currentTimeMillis()-time >= unfinishedTransactionThreshold*1000 )
                        {
                            long displayableTime = (time == 0 ? 0 : (System.currentTimeMillis()-time));
                            msgLog.logMessage( "Found old tx " + entry.getKey() + ", " + entry.getValue().first() + ", " + displayableTime );
                            try
                            {
                                Transaction otherTx = suspendOtherAndResumeThis( entry.getKey(), false );
                                finishThisAndResumeOther( otherTx, entry.getKey(), false );
                                msgLog.logMessage( "Rolled back old tx " + entry.getKey() + ", " + entry.getValue().first() + ", " + displayableTime );
                            }
                            catch ( IllegalStateException e )
                            {
                                // Expected for waiting transactions
                            }
                            catch ( Throwable t )
                            {
                                // Not really expected
                                msgLog.logMessage( "Unable to roll back old tx " + entry.getKey() + ", " + entry.getValue().first() + ", " + displayableTime );
                            }
                        }
                    }
                }
                catch ( Throwable t )
                {
                    // The show must go on
                }
            }
        }, UNFINISHED_TRANSACTION_CLEANUP_DELAY, UNFINISHED_TRANSACTION_CLEANUP_DELAY, TimeUnit.SECONDS );
    }

    public GraphDatabaseService getGraphDb()
    {
        return this.graphDb;
    }
    
    private Config getGraphDbConfig()
    {
        return ((AbstractGraphDatabase)this.graphDb).getConfig();
    }
    
    @Override
    public Response<Void> initializeTx( SlaveContext context )
    {
        Transaction otherTx = suspendOtherAndResumeThis( context, true );
        try
        {
            return packResponse( context, null );
        }
        finally
        {
            suspendThisAndResumeOther( otherTx, context );
        }
    }
    
    private Response<LockResult> acquireLock( SlaveContext context,
            LockGrabber lockGrabber, Object... entities )
    {
        Transaction otherTx = suspendOtherAndResumeThis( context, false );
        try
        {
            LockManager lockManager = getGraphDbConfig().getLockManager();
            LockReleaser lockReleaser = getGraphDbConfig().getLockReleaser();
            for ( Object entity : entities )
            {
                lockGrabber.grab( lockManager, lockReleaser, entity );
            }
            return packResponse( context, new LockResult( LockStatus.OK_LOCKED ) );
        }
        catch ( DeadlockDetectedException e )
        {
            return packResponse( context, new LockResult( e.getMessage() ) );
        }
        catch ( IllegalResourceException e )
        {
            return packResponse( context, new LockResult( LockStatus.NOT_LOCKED ) );
        }
        finally
        {
            suspendThisAndResumeOther( otherTx, context );
        }
    }
    
    private <T> Response<T> packResponse( SlaveContext context, T response )
    {
        return packResponse( context, response, MasterUtil.ALL );
    }
    
    private <T> Response<T> packResponse( SlaveContext context, T response, Predicate<Long> filter )
    {
        return MasterUtil.packResponse( graphDb, context, response, filter );
    }

    private Transaction getTxAndUpdateTimestamp( SlaveContext txId )
    {
        Pair<Transaction, AtomicLong> result = transactions.get( txId );
        
        // update time stamp to current time so that we know that this tx just completed
        // a request and can now again start to be monitored, so that it can be
        // rolled back if it's getting old.
        result.other().set( System.currentTimeMillis() );
        return result.first();
    }
    
    private Transaction getTx( SlaveContext txId )
    {
        Pair<Transaction, AtomicLong> result = transactions.get( txId );
        if ( result != null )
        {
            // set time stamp to zero so that we don't even try to finish it off
            // if getting old. This is because if the tx is active and old then
            // it means it's waiting for a lock and we cannot do anything about it.
            result.other().set( 0 );
            return result.first();
        }
        return null;
    }

    private Transaction beginTx( SlaveContext txId )
    {
        try
        {
            TransactionManager txManager = getGraphDbConfig().getTxModule().getTxManager();
            txManager.begin();
            Transaction tx = txManager.getTransaction();
            transactions.put( txId, Pair.of( tx, new AtomicLong() ) );
            return tx;
        }
        catch ( NotSupportedException e )
        {
            throw new RuntimeException( e );
        }
        catch ( SystemException e )
        {
            throw new RuntimeException( e );
        }
    }

    Transaction suspendOtherAndResumeThis( SlaveContext txId, boolean allowBegin )
    {
        try
        {
            TransactionManager txManager = getGraphDbConfig().getTxModule().getTxManager();
            Transaction otherTx = txManager.getTransaction();
            Transaction transaction = getTx( txId );
            if ( otherTx != null && otherTx == transaction )
            {
                return null;
            }
            else
            {
                if ( otherTx != null )
                {
                    txManager.suspend();
                }
                if ( transaction == null )
                {
                    if ( allowBegin )
                    {
                        beginTx( txId );
                    }
                    else
                    {
                        throw new IllegalStateException( "Transaction for " + txId +
                                " not started on this master. There may have been a master switch between the" +
                                " time this transaction started and up to now. This transaction cannot continue" +
                                " since the state from the previous master isn't transferred." );
                    }
                }
                else
                {
                    txManager.resume( transaction );
                }
                return otherTx;
            }
        }
        catch ( Exception e )
        {
            throw Exceptions.launderedException( e );
        }
    }

    void suspendThisAndResumeOther( Transaction otherTx, SlaveContext txId )
    {
        try
        {
            TransactionManager txManager = getGraphDbConfig().getTxModule().getTxManager();
            getTxAndUpdateTimestamp( txId );
            txManager.suspend();
            if ( otherTx != null )
            {
                txManager.resume( otherTx );
            }
        }
        catch ( Exception e )
        {
            throw Exceptions.launderedException( e );
        }
    }

    void finishThisAndResumeOther( Transaction otherTx, SlaveContext txId, boolean success )
    {
        try
        {
            TransactionManager txManager = getGraphDbConfig().getTxModule().getTxManager();
            if ( success ) txManager.commit();
            else txManager.rollback();
            transactions.remove( txId );
            if ( otherTx != null )
            {
                txManager.resume( otherTx );
            }
        }
        catch ( Exception e )
        {
            throw Exceptions.launderedException( e );
        }
    }

    public Response<LockResult> acquireNodeReadLock( SlaveContext context, long... nodes )
    {
        return acquireLock( context, READ_LOCK_GRABBER, nodesById( nodes ) );
    }

    public Response<LockResult> acquireNodeWriteLock( SlaveContext context, long... nodes )
    {
        return acquireLock( context, WRITE_LOCK_GRABBER, nodesById( nodes ) );
    }

    public Response<LockResult> acquireRelationshipReadLock( SlaveContext context,
            long... relationships )
    {
        return acquireLock( context, READ_LOCK_GRABBER, relationshipsById( relationships ) );
    }

    public Response<LockResult> acquireRelationshipWriteLock( SlaveContext context,
            long... relationships )
    {
        return acquireLock( context, WRITE_LOCK_GRABBER, relationshipsById( relationships ) );
    }

    public Response<LockResult> acquireGraphReadLock( SlaveContext context )
    {
        return acquireLock( context, READ_LOCK_GRABBER, graphProperties() );
    }

    public Response<LockResult> acquireGraphWriteLock( SlaveContext context )
    {
        return acquireLock( context, WRITE_LOCK_GRABBER, graphProperties() );
    }
    
    private PropertyContainer graphProperties()
    {
        return getGraphDbConfig().getGraphDbModule().getNodeManager().getGraphProperties();
    }
    
    private Node[] nodesById( long[] ids )
    {
        Node[] result = new Node[ids.length];
        for ( int i = 0; i < ids.length; i++ )
        {
            result[i] = new LockableNode( ids[i] );
        }
        return result;
    }

    private Relationship[] relationshipsById( long[] ids )
    {
        Relationship[] result = new Relationship[ids.length];
        for ( int i = 0; i < ids.length; i++ )
        {
            result[i] = new LockableRelationship( ids[i] );
        }
        return result;
    }

    public Response<IdAllocation> allocateIds( IdType idType )
    {
        IdGenerator generator = getGraphDbConfig().getIdGeneratorFactory().get( idType );
        IdAllocation result = new IdAllocation( generator.nextIdBatch( ID_GRAB_SIZE ), generator.getHighId(),
                generator.getDefragCount() );
        return MasterUtil.packResponseWithoutTransactionStream( graphDb, SlaveContext.EMPTY, result );
    }

    public Response<Long> commitSingleResourceTransaction( SlaveContext context, String resource,
            TxExtractor txGetter )
    {
        Transaction otherTx = suspendOtherAndResumeThis( context, false );
        try
        {
            XaDataSource dataSource = getGraphDbConfig().getTxModule().getXaDataSourceManager()
                    .getXaDataSource( resource );
            final long txId = dataSource.applyPreparedTransaction( txGetter.extract() );
            Predicate<Long> upUntilThisTx = new Predicate<Long>()
            {
                public boolean accept( Long item )
                {
                    return item < txId;
                }
            };
            return packResponse( context, txId, upUntilThisTx );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            suspendThisAndResumeOther( otherTx, context );
        }
    }

    public Response<Void> finishTransaction( SlaveContext context, boolean success )
    {
        Transaction otherTx = suspendOtherAndResumeThis( context, false );
        finishThisAndResumeOther( otherTx, context, success );
        return packResponse( context, null );
    }

    public Response<Integer> createRelationshipType( SlaveContext context, String name )
    {
        Config config = getGraphDbConfig();
        config.getRelationshipTypeHolder().addValidRelationshipType( name, true );
        return packResponse( context, config.getRelationshipTypeHolder().getIdFor( name ) );
    }

    public Response<Void> pullUpdates( SlaveContext context )
    {
        return packResponse( context, null );
    }

    public Response<Pair<Integer,Long>> getMasterIdForCommittedTx( long txId, StoreId storeId )
    {
        XaDataSource nioneoDataSource = getGraphDbConfig().getTxModule().getXaDataSourceManager()
                .getXaDataSource( Config.DEFAULT_DATA_SOURCE_NAME );
        try
        {
            Pair<Integer, Long> masterId = nioneoDataSource.getMasterForCommittedTx( txId );
            return MasterUtil.packResponseWithoutTransactionStream( graphDb, SlaveContext.EMPTY, masterId );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Couldn't get master ID for " + txId, e );
        }
    }

    public Response<Void> copyStore( SlaveContext context, StoreWriter writer )
    {
        context = MasterUtil.rotateLogsAndStreamStoreFiles( graphDb, true, writer );
        writer.done();
        return packResponse( context, null );
    }
    
    @Override
    public void shutdown()
    {
        unfinishedTransactionsExecutor.shutdown();
    }

//    private SlaveContext makeSureThereIsAtLeastOneKernelTx( SlaveContext context )
//    {
//        Collection<Pair<String, Long>> txs = new ArrayList<Pair<String, Long>>();
//        for ( Pair<String, Long> txEntry : context.lastAppliedTransactions() )
//        {
//            String resourceName = txEntry.first();
//            XaDataSource dataSource = graphDbConfig.getTxModule().getXaDataSourceManager()
//                    .getXaDataSource( resourceName );
//            long startedCopyAtTxId = txEntry.other();
//            if ( dataSource instanceof NeoStoreXaDataSource )
//            {
//                if ( startedCopyAtTxId == 1 ) return context;
//                if ( startedCopyAtTxId == dataSource.getLastCommittedTxId() ) startedCopyAtTxId--;
//            }
//            txs.add( Pair.of( resourceName, startedCopyAtTxId ) );
//        }
//        return new SlaveContext( context.getSessionId(), context.machineId(),
//                context.getEventIdentifier(), txs.toArray( new Pair[0] ) );
//    }

    private static interface LockGrabber
    {
        void grab( LockManager lockManager, LockReleaser lockReleaser, Object entity );
    }

    private static LockGrabber READ_LOCK_GRABBER = new LockGrabber()
    {
        public void grab( LockManager lockManager, LockReleaser lockReleaser, Object entity )
        {
            lockManager.getReadLock( entity );
            lockReleaser.addLockToTransaction( entity, LockType.READ );
        }
    };

    private static LockGrabber WRITE_LOCK_GRABBER = new LockGrabber()
    {
        public void grab( LockManager lockManager, LockReleaser lockReleaser, Object entity )
        {
            lockManager.getWriteLock( entity );
            lockReleaser.addLockToTransaction( entity, LockType.WRITE );
        }
    };
    
    @Override
    public Response<LockResult> acquireIndexReadLock( SlaveContext context, String index, String key )
    {
        return acquireLock( context, READ_LOCK_GRABBER, new NodeManager.IndexLock( index, key ) );
    }
    
    @Override
    public Response<LockResult> acquireIndexWriteLock( SlaveContext context, String index,
            String key )
    {
        return acquireLock( context, WRITE_LOCK_GRABBER, new NodeManager.IndexLock( index, key ) );
    }

    // =====================================================================
    // Just some methods which aren't really used when running a HA cluster,
    // but exposed so that other tools can reach that information.
    // =====================================================================

    public Map<Integer, Collection<SlaveContext>> getOngoingTransactions()
    {
        Map<Integer, Collection<SlaveContext>> result = new HashMap<Integer, Collection<SlaveContext>>();
        for ( SlaveContext context : transactions.keySet() )
        {
            Collection<SlaveContext> txs = result.get( context.machineId() );
            if ( txs == null )
            {
                txs = new ArrayList<SlaveContext>();
                result.put( context.machineId(), txs );
            }
            txs.add( context );
        }
        return result;
    }
}
