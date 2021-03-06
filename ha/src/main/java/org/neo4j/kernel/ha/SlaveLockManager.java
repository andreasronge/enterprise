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

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import org.neo4j.com.ComException;
import org.neo4j.com.Response;
import org.neo4j.com.SlaveContext;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.kernel.DeadlockDetectedException;
import org.neo4j.kernel.LockManagerFactory;
import org.neo4j.kernel.ha.zookeeper.ZooKeeperException;
import org.neo4j.kernel.impl.core.GraphProperties;
import org.neo4j.kernel.impl.core.NodeManager.IndexLock;
import org.neo4j.kernel.impl.transaction.IllegalResourceException;
import org.neo4j.kernel.impl.transaction.LockManager;
import org.neo4j.kernel.impl.transaction.TxHook;
import org.neo4j.kernel.impl.transaction.TxManager;
import org.neo4j.kernel.impl.transaction.TxModule;

public class SlaveLockManager extends LockManager
{
    public static class SlaveLockManagerFactory implements LockManagerFactory
    {
        private final Broker broker;
        private final ResponseReceiver receiver;

        public SlaveLockManagerFactory( Broker broker, ResponseReceiver receiver )
        {
            this.broker = broker;
            this.receiver = receiver;
        }
        
        public LockManager create( TxModule txModule )
        {
            return new SlaveLockManager( txModule.getTxManager(), txModule.getTxHook(), broker, receiver );
        }
    };
    
    private final Broker broker;
    private final TransactionManager tm;
    private final ResponseReceiver receiver;
    private final TxHook txHook;
    
    public SlaveLockManager( TransactionManager tm, TxHook txHook, Broker broker, ResponseReceiver receiver )
    {
        super( tm );
        this.tm = tm;
        this.txHook = txHook;
        this.broker = broker;
        this.receiver = receiver;
    }

    private int getLocalTxId()
    {
        return ((TxManager) tm).getEventIdentifier();
    }
    
    @Override
    public void getReadLock( Object resource ) throws DeadlockDetectedException,
            IllegalResourceException
    {
        LockGrabber grabber = null;
        if ( resource instanceof Node ) grabber = LockGrabber.NODE_READ;
        else if ( resource instanceof Relationship ) grabber = LockGrabber.RELATIONSHIP_READ;
        else if ( resource instanceof GraphProperties ) grabber = LockGrabber.GRAPH_READ;
        else if ( resource instanceof IndexLock ) grabber = LockGrabber.INDEX_READ;

        try
        {
            if ( grabber == null )
            {
                super.getReadLock( resource );
                return;
            }
            
            initializeTxIfFirst();
            LockResult result = null;
            do
            {
                int eventIdentifier = getLocalTxId();
                result = receiver.receive( grabber.acquireLock( broker.getMaster().first(),
                        receiver.getSlaveContext( eventIdentifier ), resource ) );
                switch ( result.getStatus() )
                {
                case OK_LOCKED:
                    super.getReadLock( resource );
                    return;
                case DEAD_LOCKED:
                    throw new DeadlockDetectedException( result.getDeadlockMessage() );
                }
            }
            while ( result.getStatus() == LockStatus.NOT_LOCKED );
        }
        catch ( ZooKeeperException e )
        {
            receiver.newMaster( e );
            throw e;
        }
        catch ( ComException e )
        {
            receiver.newMaster( e );
            throw e;
        }
    }

    private void initializeTxIfFirst()
    {
        // The main point of initializing transaction (for HA) is in TransactionImpl, so this is
        // for that extra point where grabbing a lock
        try
        {
            Transaction tx = tm.getTransaction();
            if ( !txHook.hasAnyLocks( tx ) ) txHook.initializeTransaction( ((TxManager)tm).getEventIdentifier() );
        }
        catch ( SystemException e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public void getWriteLock( Object resource ) throws DeadlockDetectedException,
            IllegalResourceException
    {
        // Code copied from getReadLock. Fix!
        LockGrabber grabber = null;
        if ( resource instanceof Node ) grabber = LockGrabber.NODE_WRITE;
        else if ( resource instanceof Relationship ) grabber = LockGrabber.RELATIONSHIP_WRITE;
        else if ( resource instanceof GraphProperties ) grabber = LockGrabber.GRAPH_WRITE;
        else if ( resource instanceof IndexLock ) grabber = LockGrabber.INDEX_WRITE;

        try
        {
            if ( grabber == null )
            {
                super.getWriteLock( resource );
                return;
            }
            
            initializeTxIfFirst();
            LockResult result = null;
            do
            {
                int eventIdentifier = getLocalTxId();
                result = receiver.receive( grabber.acquireLock( broker.getMaster().first(),
                        receiver.getSlaveContext( eventIdentifier ), resource ) );
                switch ( result.getStatus() )
                {
                case OK_LOCKED:
                    super.getWriteLock( resource );
                    return;
                case DEAD_LOCKED:
                    throw new DeadlockDetectedException( result.getDeadlockMessage() );
                }
            }
            while ( result.getStatus() == LockStatus.NOT_LOCKED );
        }
        catch ( ZooKeeperException e )
        {
            receiver.newMaster( e );
            throw e;
        }
        catch ( ComException e )
        {
            receiver.newMaster( e );
            throw e;
        }
    }
    
    // Release lock is as usual, since when the master committs it will release
    // the locks there and then when this slave committs it will release its
    // locks as usual here.
    
    private static enum LockGrabber
    {
        NODE_READ
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireNodeReadLock( context, ((Node)resource).getId() );
            }
        },
        NODE_WRITE
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireNodeWriteLock( context, ((Node)resource).getId() );
            }
        },
        RELATIONSHIP_READ
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireRelationshipReadLock( context, ((Relationship)resource).getId() );
            }
        },
        RELATIONSHIP_WRITE
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireRelationshipWriteLock( context, ((Relationship)resource).getId() );
            }
        },
        GRAPH_READ
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireGraphReadLock( context );
            }
        },
        GRAPH_WRITE
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                return master.acquireGraphWriteLock( context );
            }
        },
        INDEX_WRITE
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                IndexLock lock = (IndexLock) resource;
                return master.acquireIndexWriteLock( context, lock.getIndex(), lock.getKey() );
            }
        },
        INDEX_READ
        {
            @Override
            Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource )
            {
                IndexLock lock = (IndexLock) resource;
                return master.acquireIndexReadLock( context, lock.getIndex(), lock.getKey() );
            }
        };
        
        abstract Response<LockResult> acquireLock( Master master, SlaveContext context, Object resource );
    }
}
