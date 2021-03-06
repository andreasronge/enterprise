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
package slavetest;

import org.neo4j.helpers.Args;
import org.neo4j.kernel.HaConfig;
import org.neo4j.kernel.ha.zookeeper.ClusterManager;
import org.neo4j.kernel.ha.zookeeper.Machine;

public class DumpZooInfo
{
    public static void main( String[] args )
    {
        Args arguments = new Args( args );
        ClusterManager clusterManager = new ClusterManager( "localhost", arguments.get( HaConfig.CONFIG_KEY_CLUSTER_NAME, HaConfig.CONFIG_DEFAULT_HA_CLUSTER_NAME ) );
        clusterManager.waitForSyncConnected();
        System.out.println( "Master is " + clusterManager.getCachedMaster() );
        System.out.println( "Connected slaves" );
        for ( Machine info : clusterManager.getConnectedSlaves() )
        {
            System.out.println( "\t" + info );
        }
//        System.out.println( "Disconnected slaves" );
//        for ( Machine info : clusterManager.getDisconnectedSlaves() )
//        {
//            System.out.println( "\t" + info );
//        }
        clusterManager.shutdown();
    }
}
