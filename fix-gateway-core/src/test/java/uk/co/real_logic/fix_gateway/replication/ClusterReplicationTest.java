/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.replication;

import io.aeron.logbuffer.BufferClaim;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import uk.co.real_logic.fix_gateway.DebugLogger;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.aeron.protocol.DataHeaderFlyweight.HEADER_LENGTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.*;

/**
 * Test simulated cluster.
 */
public class ClusterReplicationTest
{

    private static final int BUFFER_SIZE = 16;
    private static final int POSITION_AFTER_MESSAGE = BUFFER_SIZE + HEADER_LENGTH;

    private BufferClaim bufferClaim = new BufferClaim();
    private UnsafeBuffer buffer = new UnsafeBuffer(new byte[BUFFER_SIZE]);

    private final NodeRunner node1 = new NodeRunner(1, 2, 3);
    private final NodeRunner node2 = new NodeRunner(2, 1, 3);
    private final NodeRunner node3 = new NodeRunner(3, 1, 2);
    private final NodeRunner[] allNodes = { node1, node2, node3 };

    @Rule
    public Timeout timeout = Timeout.seconds(10);

    @Before
    public void hasElectedLeader()
    {
        while (!foundLeader())
        {
            pollAll();
        }

        final NodeRunner leader = leader();
        DebugLogger.log("Leader elected: %d\n\n", leader.raftNode().nodeId());
    }

    @Test
    public void shouldEstablishCluster()
    {
        checkClusterStable();

        assertNodeStateReplicated();
    }

    @Test
    public void shouldReplicateMessage()
    {
        final NodeRunner leader = leader();

        DebugLogger.log("Leader is %s\n", leader.raftNode().nodeId());

        final long position = sendMessageTo(leader);

        DebugLogger.log("Leader @ %s\n", position);

        assertMessageReceived();
    }

    @Test
    public void shouldReformClusterAfterLeaderPause()
    {
        awaitLeadershipConcensus();

        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        while (!foundLeader(followers))
        {
            poll(followers);
        }

        assertBecomesFollower(leader);
    }

    @Test
    public void shouldReformClusterAfterLeaderNetsplit()
    {
        leaderNetSplitScenario(true, true);
    }

    @Test
    public void shouldReformClusterAfterPartialLeaderNetsplit()
    {
        // NB: under other partial failure, the leader would never stop being a leader
        leaderNetSplitScenario(false, true);
    }

    private void leaderNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner leader = leader();
        final NodeRunner[] followers = followers();

        leader.dropFrames(dropInboundFrames, dropOutboundFrames);

        assertElectsNewLeader(followers);

        leader.dropFrames(false);

        assertBecomesFollower(leader);
    }

    @Test
    public void shouldRejoinClusterAfterFollowerNetsplit()
    {
        // NB: under other partial failure, the follower would never stop being a follower
        followerNetSplitScenario(true, true);
    }

    @Test
    public void shouldRejoinClusterAfterPartialFollowerNetsplit()
    {
        followerNetSplitScenario(true, false);
    }

    private void followerNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner follower = aFollower();

        follower.dropFrames(dropInboundFrames, dropOutboundFrames);

        assertBecomesCandidate(follower);

        follower.dropFrames(false);

        eventuallyOneLeaderAndTwoFollowers();
    }

    @Test
    public void shouldReformClusterAfterFollowerNetsplit()
    {
        clusterNetSplitScenario(true, true);
    }

    @Test
    public void shouldReformClusterAfterPartialFollowerNetsplit()
    {
        clusterNetSplitScenario(true, false);
    }

    private void clusterNetSplitScenario(final boolean dropInboundFrames, final boolean dropOutboundFrames)
    {
        final NodeRunner[] followers = followers();

        nodes().forEach(nodeRunner -> nodeRunner.dropFrames(dropInboundFrames, dropOutboundFrames));

        assertBecomesCandidate(followers);

        nodes().forEach(nodeRunner -> nodeRunner.dropFrames(false));

        assertBecomesFollower(followers);

        eventuallyOneLeaderAndTwoFollowers();
    }

    @Test
    public void shouldNotReplicateMessageUntilClusterReformed()
    {
        final NodeRunner leader = leader();
        final NodeRunner follower = aFollower();

        follower.dropFrames(true);

        assertBecomesCandidate(follower);

        sendMessageTo(leader);

        assertTrue("nodes received message when one was supposedly netsplit",
            noNodesReceivedMessage());

        follower.dropFrames(false);

        assertBecomesFollower(follower);

        assertMessageReceived();
    }

    private void assertNodeStateReplicated()
    {
        final NodeRunner leader = leader();
        final Int2IntHashMap nodeIdToId = leader.nodeIdToId();
        final short leaderId = leader.raftNode().nodeId();

        for (final int id : new int[]{1, 2, 3})
        {
            if (id != leaderId)
            {
                assertThat(nodeIdToId, hasEntry(id, id));
            }
        }
    }

    private NodeRunner aFollower()
    {
        return followers()[0];
    }

    private void assertBecomesCandidate(final NodeRunner ... nodes)
    {
        assertBecomes(ClusterAgent::isCandidate, allNodes, nodes);
    }

    private void assertBecomesFollower(final NodeRunner ... nodes)
    {
        assertBecomes(ClusterAgent::isFollower, allNodes, nodes);
    }

    private void assertBecomes(
        final Predicate<ClusterAgent> predicate,
        final NodeRunner[] toPoll,
        final NodeRunner... nodes)
    {
        final ClusterAgent[] clusterNodes = getRaftNodes(nodes);
        while (!allMatch(clusterNodes, predicate))
        {
            poll(toPoll);
        }
        assertTrue(allMatch(clusterNodes, predicate));
    }

    private ClusterAgent[] getRaftNodes(final NodeRunner[] nodes)
    {
        return Stream.of(nodes).map(NodeRunner::raftNode).toArray(ClusterAgent[]::new);
    }

    private static <T> boolean allMatch(final T[] values, final Predicate<T> predicate)
    {
        return Stream.of(values).allMatch(predicate);
    }

    private void assertElectsNewLeader(final NodeRunner ... followers)
    {
        while (!foundLeader(followers))
        {
            pollAll();
        }
    }

    private void assertMessageReceived()
    {
        while (noNodesReceivedMessage())
        {
            pollAll();
        }
    }

    private boolean noNodesReceivedMessage()
    {
        return notReceivedMessage(node1) && notReceivedMessage(node2) && notReceivedMessage(node3);
    }

    private void checkClusterStable()
    {
        for (int i = 0; i < 100; i++)
        {
            pollAll();
        }

        eventuallyOneLeaderAndTwoFollowers();

        assertAllNodesSeeSameLeader();

        DebugLogger.log("Cluster Stable");
    }

    private void awaitLeadershipConcensus()
    {
        while (!(node1.leaderSessionId() == node2.leaderSessionId() &&
                 node1.leaderSessionId() == node3.leaderSessionId()))
        {
            pollAll();
        }
    }

    private void assertAllNodesSeeSameLeader()
    {
        final int leaderSessionId = node1.leaderSessionId();
        assertEquals("1 and 2 disagree on leader", leaderSessionId, node2.leaderSessionId());
        assertEquals("1 and 3 disagree on leader", leaderSessionId, node3.leaderSessionId());
    }

    private boolean notReceivedMessage(final NodeRunner node)
    {
        return node.replicatedPosition() < POSITION_AFTER_MESSAGE;
    }

    private long sendMessageTo(final NodeRunner leader)
    {
        final ClusterablePublication publication = leader.raftNode().clusterStreams().publication(1);

        long position;
        while (true)
        {
            position = publication.tryClaim(BUFFER_SIZE, bufferClaim);
            if (position > 0)
            {
                bufferClaim
                    .buffer()
                    .putBytes(bufferClaim.offset(), buffer, 0, BUFFER_SIZE);
                bufferClaim.commit();
                return position;
            }
            pause();
            pollAll();
        }
    }

    private void pause()
    {
        LockSupport.parkNanos(1000);
    }

    private void pollAll()
    {
        poll(allNodes);
    }

    private void poll(final NodeRunner ... nodes)
    {
        final int fragmentLimit = 10;
        for (final NodeRunner node : nodes)
        {
            node.poll(fragmentLimit);
        }
        LockSupport.parkNanos(MILLISECONDS.toNanos(1));
    }

    private boolean foundLeader()
    {
        return foundLeader(node1, node2, node3);
    }

    private boolean foundLeader(NodeRunner ... nodes)
    {
        final long leaderCount = Stream.of(nodes).filter(NodeRunner::isLeader).count();
        return leaderCount == 1;
    }

    private void eventuallyOneLeaderAndTwoFollowers()
    {
        while (!oneLeaderAndTwoFollowers())
        {
            pollAll();
        }
    }

    private boolean oneLeaderAndTwoFollowers()
    {
        int leaderCount = 0;
        int followerCount = 0;

        for (final NodeRunner node: allNodes)
        {
            if (node.isLeader())
            {
                leaderCount++;
            }
            else if (node.raftNode().isFollower())
            {
                followerCount++;
            }
        }

        return leaderCount == 1 && followerCount == 2;
    }

    private NodeRunner leader()
    {
        return nodes()
            .filter(NodeRunner::isLeader)
            .findFirst()
            .get(); // Just error the test if there's not a leader
    }

    private NodeRunner[] followers()
    {
        return nodes().filter(node -> !node.isLeader()).toArray(NodeRunner[]::new);
    }

    private Stream<NodeRunner> nodes()
    {
        return Stream.of(node1, node2, node3);
    }

    @After
    public void shutdown()
    {
        node1.close();
        node2.close();
        node3.close();
    }

}
