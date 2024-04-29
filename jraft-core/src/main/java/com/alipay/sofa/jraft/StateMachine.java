/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.sofa.jraft;

import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.LeaderChangeContext;
import com.alipay.sofa.jraft.error.RaftException;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;

/**
 * |StateMachine| is the sink of all the events of a very raft node.
 * Implement a specific StateMachine for your own business logic.
 * NOTE: All the interfaces are not guaranteed to be thread safe and they are
 * called sequentially, saying that every single operation will block all the
 * following ones.
 *
 * @author boyan (boyan@alibaba-inc.com)
 *
 * 2018-Apr-08 5:43:21 PM
 */
public interface StateMachine {

    /**
     * Update the StateMachine with a batch a tasks that can be accessed
     * through |iterator|.
     *
     * Invoked when one or more tasks that were passed to Node#apply(Task) have been
     * committed to the raft group (quorum of the group peers have received
     * those tasks and stored them on the backing storage).
     *
     * Once this function returns to the caller, we will regard all the iterated
     * tasks through |iter| have been successfully applied. And if you didn't
     * apply all the the given tasks, we would regard this as a critical error
     * and report a error whose type is ERROR_TYPE_STATE_MACHINE.
     *
     * 最核心的方法，应用任务列表到状态机，任务将按照提交顺序应用。
     * 请注意，当这个方法返回的时候，我们就认为这一批任务都已经成功应用到状态机上，
     * 如果你没有完全应用（比如错误、异常），将会被当做一个 critical 级别的错误，\
     * 报告给状态机的 onError 方法，错误类型为 ERROR_TYPE_STATE_MACHINE
     *
     * @param iter iterator of states
     */
    void onApply(final Iterator iter);

    /**
     * Invoked once when the raft node was shut down.
     * Default do nothing
     *
     * 当状态机所在 raft 节点被关闭的时候调用，可以用于一些状态机的资源清理工作，比如关闭文件等。
     */
    void onShutdown();

    /**
     * User defined snapshot generate function, this method will block StateMachine#onApply(Iterator).
     * user can make snapshot async when fsm can be cow(copy-on-write).
     * call done.run(status) when snapshot finished.
     * Default: Save nothing and returns error.
     *
     * Snapshot 的保存
     *
     * @param writer snapshot writer
     * @param done   callback
     */
    void onSnapshotSave(final SnapshotWriter writer, final Closure done);

    /**
     * User defined snapshot load function
     * get and load snapshot
     * Default: Load nothing and returns error.
     *
     * Snapshot 的加载
     *
     * @param reader snapshot reader
     * @return true on success
     */
    boolean onSnapshotLoad(final SnapshotReader reader);

    /**
     * Invoked when the belonging node becomes the leader of the group at |term|
     * Default: Do nothing
     *
     * 当状态机所属的 raft 节点成为 leader 的时候被调用，成为 leader 当前的 term 通过参数传入。
     *
     * @param term new term num
     */
    void onLeaderStart(final long term);

    /**
     * Invoked when this node steps down from the leader of the replication
     * group and |status| describes detailed information
     *
     * 当前状态机所属的 raft 节点失去 leader 资格时调用，status 字段描述了详细的原因，比如主动转移 leadership、重新发生选举等
     *
     * @param status status info
     */
    void onLeaderStop(final Status status);

    /**
     * This method is called when a critical error was encountered, after this
     * point, no any further modification is allowed to applied to this node
     * until the error is fixed and this node restarts.
     *
     * 当 critical 错误发生的时候，会调用此方法，RaftException 包含了 status 等详细的错误信息；
     * 当这个方法被调用后，将不允许新的任务应用到状态机，直到错误被修复并且节点被重启。
     *
     * @param e raft error message
     */
    void onError(final RaftException e);

    /**
     * Invoked when a configuration has been committed to the group.
     *
     * 当一个 raft group 的节点配置提交到 raft group 日志的时候调用，通常不需要实现此方法，或者打印个日志即可
     *
     * @param conf committed configuration
     */
    void onConfigurationCommitted(final Configuration conf);

    /**
     * This method is called when a follower stops following a leader and its leaderId becomes null,
     * situations including:
     * 1. handle election timeout and start preVote
     * 2. receive requests with higher term such as VoteRequest from a candidate
     *    or appendEntries request from a new leader
     * 3. receive timeoutNow request from current leader and start request vote.
     * 
     * the parameter ctx gives the information(leaderId, term and status) about the
     * very leader whom the follower followed before.
     * User can reset the node's information as it stops following some leader.
     *
     *
     * 当一个 raft follower 停止 follower 一个 leader 节点的时候调用，这种情况一般是发生了 leadership 转移，
     * 比如重新选举产生了新的 leader，或者进入选举阶段等。
     * 同样 LeaderChangeContext 描述了停止 follow 的 leader 的信息，其中 status 描述了停止 follow 的原因。
     *
     * @param ctx context of leader change
     */
    void onStopFollowing(final LeaderChangeContext ctx);

    /**
     * This method is called when a follower or candidate starts following a leader and its leaderId
     * (should be NULL before the method is called) is set to the leader's id,
     * situations including:
     * 1. a candidate receives appendEntries request from a leader
     * 2. a follower(without leader) receives appendEntries from a leader
     * 
     * the parameter ctx gives the information(leaderId, term and status) about
     * the very leader whom the follower starts to follow.
     * User can reset the node's information as it starts to follow some leader.
     *
     * 当一个 raft follower 或者 candidate 节点开始 follow 一个 leader 的时候调用，
     * LeaderChangeContext 包含了 leader 的 PeerId/term/status 等上下文信息。
     * 并且当前 raft node 的 leaderId 属性会被设置为新的 leader 节点 PeerId
     *
     * @param ctx context of leader change
     */
    void onStartFollowing(final LeaderChangeContext ctx);
}
