package riff.raft.node
import riff.raft.RoleCallback.{RoleChangeEvent, RoleEvent}
import riff.raft.log.{LogAppendResult, LogCoords, LogEntry, RaftLog}
import riff.raft.messages.{RaftResponse, _}
import riff.raft.timer.{RaftClock, TimerCallback, Timers}
import riff.raft.{NodeId, RoleCallback, Term, node}

object RaftNode {

  def inMemory[A](id: NodeId, maxAppendSize: Int = 10)(implicit clock: RaftClock): RaftNode[A] = {
    new RaftNode[A](
      PersistentState.inMemory().cached(),
      RaftLog.inMemory[A](),
      new Timers(clock),
      RaftCluster(Nil),
      node.FollowerNodeState(id, None),
      maxAppendSize
    )
  }
}

/**
  * The place where the different pieces which represent a Raft Node come together -- the glue code.
  *
  * I've looked at this a few different ways, but ultimately found this abstraction here to be the most readable,
  * and follows most closely what's laid out in the raft spec.
  *
  * It's not too generic/abstracted, but quite openly just orchestrates the pieces/interactions of the inputs
  * into a raft node.
  *
  * @param persistentState the state used to track the currentTerm and vote state
  * @param log the RaftLog which stores the log entries
  * @param clock the timing apparatus for controlling timeouts
  * @param cluster this node's view of the cluster
  * @param initialState the initial role
  * @param maxAppendSize the maximum number of entries to send to a follower at a time when catching up the log
  * @param initialTimerCallback the callback which gets passed to the raft timer. If unspecified (e.g., left null), then 'this' RaftNode
  *                             will be used. In most usages, this **should** be set to an exterior callback which invokes this RaftNode
  *                             and does something with the result, since the result of a timeout is meaningful, as it will be a set of [[RequestVote]] messages, etc
  * @tparam A the log entry type
  */
class RaftNode[A](
  val persistentState: PersistentState,
  val log: RaftLog[A],
  val timers: Timers,
  val cluster: RaftCluster,
  initialState: NodeState,
  val maxAppendSize: Int,
  initialTimerCallback: TimerCallback[_] = null,
  roleCallback: RoleCallback = RoleCallback.NoOp)
    extends RaftMessageHandler[A] with TimerCallback[RaftNodeResult[A]] { self =>

  private val timerCallback = Option(initialTimerCallback).getOrElse(this)

  private var currentState: NodeState = initialState

  private def updateState(newState: NodeState) = {
    val b4 = currentState.role
    currentState = newState
    if (b4 != currentState.role) {
      roleCallback.onEvent(RoleChangeEvent(currentTerm(), b4, currentState.role))
    }
  }

  def nodeId: NodeId = currentState.id

  def state(): NodeState = currentState

  /** This function may be used as a convenience to generate append requests for a
    *
    * @param data the data to append
    * @return either some append requests or an error output if we are not the leader
    */
  def createAppend(data: Array[A]): RaftNodeResult[A] = {
    appendIfLeader(data) match {
      case None =>
        val leaderMsg = currentState.leader.fold("")(name => s". The leader is ${name}")
        NoOpResult(s"Can't create an append request as we are ${currentState.role} in term ${currentTerm}$leaderMsg")
      case Some((_, requests)) => requests
    }
  }

  /**
    * Exposes this as a means for generating an AddressedRequest of messages together with the append result
    * from the leader's log
    *
    * @param data the data to append
    * @return the append result coupled w/ the append request to send if this node is the leader
    */
  def appendIfLeader(data: Array[A]): Option[(LogAppendResult, AddressedRequest[A])] = {
    currentState match {
      case leader: LeaderNodeState =>
        val res = leader.makeAppendEntries[A](log, currentTerm(), data)
        Option(res)
      case _ => None
    }
  }

  override def onMessage(input: RaftMessage[A]): Result = {
    input match {
      case AddressedMessage(from, msg: RequestOrResponse[A]) => handleMessage(from, msg)
      case timer: TimerMessage => onTimerMessage(timer)
      case AppendData(data) => createAppend(data)
    }
  }

  /**
    * Applies requests and responses coming to the node state and replies w/ any resulting messages
    *
    * @param from the node from which this message is received
    * @param msg the Raft message
    * @return and resulting messages (requests or responses)
    */
  def handleMessage(from: NodeId, msg: RequestOrResponse[A]): Result = {
    msg match {
      case request: RaftRequest[A] => AddressedResponse(from, onRequest(from, request))
      case reply: RaftResponse => onResponse(from, reply)
    }
  }

  /**
    * Handle a response coming from 'from'
    *
    * @param from the originating node
    * @param reply the response
    * @return any messages resulting from having processed this response
    */
  def onResponse(from: NodeId, reply: RaftResponse): Result = {
    reply match {
      case voteResponse: RequestVoteResponse => onRequestVoteResponse(from, voteResponse)
      case appendResponse: AppendEntriesResponse =>
        val (_, result) = onAppendEntriesResponse(from, appendResponse)
        result
    }
  }

  def onRequestVoteResponse(from: NodeId, voteResponse: RequestVoteResponse): Result = {
    currentState match {
      case candidate: CandidateNodeState =>
        val newState = candidate.onRequestVoteResponse(from, cluster, voteResponse)
        updateState(newState)

        if (currentState.isLeader) {
          // notify leader change
          onBecomeLeader()
        } else {
          NoOpResult(s"Got vote ${voteResponse}, vote state is now : ${candidate.candidateState()}")
        }
      case _ =>
        NoOpResult(s"Got vote ${voteResponse} while in role ${currentState.role}, term ${currentTerm}")
    }
  }

  /**
    * We're either the leader and should update our peer view/commit log, or aren't and should ignore it
    *
    * @param appendResponse
    * @return the result
    */
  def onAppendEntriesResponse(from: NodeId, appendResponse: AppendEntriesResponse): (Seq[LogCoords], Result) = {
    currentState match {
      case leader: LeaderNodeState =>
        leader.onAppendResponse(from, log, currentTerm, appendResponse, maxAppendSize)
      case _ =>
        val result = NoOpResult(
          s"Ignoring append response from $from as we're in role ${currentState.role}, term ${currentTerm}")
        (Nil, result)

    }
  }

  def onTimerMessage(timeout: TimerMessage): Result = {
    timeout match {
      case ReceiveHeartbeatTimeout => onReceiveHeartbeatTimeout()
      case SendHeartbeatTimeout => onSendHeartbeatTimeout()
    }
  }

  private def createAppendOnHeartbeatTimeout(leader: LeaderNodeState, toNode: NodeId) = {
    leader.clusterView.stateForPeer(toNode) match {
      // we don't know what state this node is in - send our default heartbeat
      case None => makeDefaultHeartbeat()

      // we're at the beginning of the log - send some data
      case Some(Peer(1, 0)) =>
        AppendEntries(LogCoords.Empty, currentTerm, log.latestCommit(), log.entriesFrom(1, maxAppendSize))

      // the state where we've not yet matched a peer, so we're trying to send ever-decreasing indices,
      // using the 'nextIndex' (which should be decrementing on each fail)
      case Some(Peer(nextIndex, 0)) =>
        log.coordsForIndex(nextIndex) match {
          // "This should never happen" (c)
          // potential error situation where we have a 'nextIndex' -- resend the default heartbeat
          case None => makeDefaultHeartbeat()
          case Some(previous) => AppendEntries(previous, currentTerm, log.latestCommit(), Array.empty[LogEntry[A]])
        }

      // the normal success state where we send our expected previous coords based on the match index
      case Some(Peer(nextIndex, matchIndex)) =>
        log.coordsForIndex(matchIndex) match {
          case None =>
            // "This should never happen" (c)
            // potential error situation where we have a 'nextIndex' -- resend the default heartbeat
            makeDefaultHeartbeat()
          case Some(previous) =>
            AppendEntries(previous, currentTerm, log.latestCommit(), log.entriesFrom(nextIndex, maxAppendSize))
        }
    }
  }

  def onSendHeartbeatTimeout(): Result = {
    currentState match {
      case leader: LeaderNodeState =>
        resetSendHeartbeat()

        val msgs = cluster.peers.map { toNode =>
          val heartbeat = createAppendOnHeartbeatTimeout(leader, toNode)
          toNode -> heartbeat
        }

        AddressedRequest(msgs)
      case _ =>
        NoOpResult(s"Received send heartbeat timeout, but we're ${currentState.role} in term ${currentTerm}")
    }
  }

  private def makeDefaultHeartbeat() =
    AppendEntries(log.latestAppended(), currentTerm, log.latestCommit(), Array.empty[LogEntry[A]])

  def onReceiveHeartbeatTimeout(): Result = {
    onBecomeCandidateOrLeader()
  }

  def onRequest(from: NodeId, request: RaftRequest[A]): RaftResponse = {
    request match {
      case append: AppendEntries[A] => onAppendEntries(from, append)
      case vote: RequestVote => onRequestVote(from, vote)
    }
  }

  def onAppendEntries(from: NodeId, append: AppendEntries[A]): AppendEntriesResponse = {
    val beforeTerm = currentTerm
    val doAppend = if (beforeTerm < append.term) {
      onBecomeFollower(Option(from), append.term)
      false
    } else if (beforeTerm > append.term) {
      // ignore/fail if we get an append for an earlier term
      false
    } else {
      // we're supposedly the leader of this term ... ???
      currentState match {
        case _: LeaderNodeState => false
        case follower @ FollowerNodeState(_, None) =>
          updateState(follower.copy(leader = Option(from)))
          roleCallback.onNewLeader(currentTerm, from)
          resetReceiveHeartbeat()
          true
        case _ =>
          resetReceiveHeartbeat()
          true
      }
    }

    if (doAppend) {
      val result: AppendEntriesResponse = log.onAppend(currentTerm, append)
      if (result.success) {
        log.commit(append.commitIndex)
      }
      result
    } else {
      AppendEntriesResponse.fail(currentTerm())
    }
  }

  /**
    * Create a reply to the given vote request.
    *
    * NOTE: Whatever the actual node 'A' is, it is expected that, upon a successful reply,
    * it updates it's own term and writes down (remembers) that it voted in this term so
    * as not to double-vote should this node crash.
    *
    * @param forRequest the data from the vote request
    * @return the RequestVoteResponse
    */
  def onRequestVote(from: NodeId, forRequest: RequestVote): RequestVoteResponse = {
    val beforeTerm = currentTerm
    val reply = persistentState.castVote(log.latestAppended(), from, forRequest)

    // regardless of granting the vote or not, if we just saw a later term, we need to be a follower
    // ... and if we are (soon to be were) leader, we have to transition (cancel heartbeats, etc)
    if (beforeTerm < reply.term) {
      onBecomeFollower(None, reply.term)
    }
    reply
  }

  def onBecomeCandidateOrLeader(): AddressedRequest[A] = {
    val newTerm = currentTerm + 1
    persistentState.currentTerm = newTerm

    // write down that we're voting for ourselves
    persistentState.castVote(newTerm, nodeId)

    // this election may end up being a split-brain, or we may have been disconnected. At any rate,
    // we need to reset our heartbeat timeout
    resetReceiveHeartbeat()

    cluster.numberOfPeers match {
      case 0 =>
        updateState(currentState.becomeLeader(cluster))
        onBecomeLeader()
      case clusterSize =>
        updateState(currentState.becomeCandidate(newTerm, clusterSize + 1))
        val requestVote = RequestVote(newTerm, log.latestAppended())
        AddressedRequest(cluster.peers.map(_ -> requestVote))
    }
  }

  def onBecomeFollower(newLeader: Option[NodeId], newTerm: Term) = {
    if (currentState.isLeader) {
      // cancel HB for all nodes
      cancelSendHeartbeat()
    }
    resetReceiveHeartbeat()
    persistentState.currentTerm = newTerm
    newLeader.foreach { leaderId => roleCallback.onNewLeader(currentTerm(), leaderId)
    }
    updateState(currentState.becomeFollower(newLeader))
  }

  def onBecomeLeader(): AddressedRequest[A] = {
    val hb = makeDefaultHeartbeat()
    cancelReceiveHeartbeat()
    resetSendHeartbeat()
    roleCallback.onNewLeader(currentTerm, nodeId)
    AddressedRequest(cluster.peers.map(_ -> hb))
  }

  def cancelReceiveHeartbeat(): Unit = {
    timers.receiveHeartbeat.cancel()
  }

  def cancelSendHeartbeat(): Unit = {
    timers.sendHeartbeat.cancel()
  }

  def resetSendHeartbeat(): timers.clock.CancelT = {
    timers.sendHeartbeat.reset(timerCallback)
  }

  def resetReceiveHeartbeat(): timers.clock.CancelT = {
    timers.receiveHeartbeat.reset(timerCallback)
  }

  def currentTerm(): Term = persistentState.currentTerm

  /** a convenience builder method to create a new raft node w/ the given raft log
    *
    * @return a new node state
    */
  def withLog(newLog: RaftLog[A]): RaftNode[A] = {
    new RaftNode(persistentState, newLog, timers, cluster, state, maxAppendSize, timerCallback, roleCallback)
  }

  /** a convenience builder method to create a new raft node w/ the given cluster
    *
    * @return a new node state
    */
  def withCluster(newCluster: RaftCluster): RaftNode[A] = {
    new RaftNode(persistentState, log, timers, newCluster, state, maxAppendSize, timerCallback, roleCallback)
  }

  def withTimerCallback(newTimerCallback: TimerCallback[_]): RaftNode[A] = {
    new RaftNode(persistentState, log, timers, cluster, state, maxAppendSize, newTimerCallback, roleCallback)
  }

  def withRoleCallback(f: RoleEvent => Unit): RaftNode[A] = withRoleCallback(RoleCallback(f))

  def withRoleCallback(newRoleCallback: RoleCallback): RaftNode[A] = {
    new RaftNode(persistentState, log, timers, cluster, state, maxAppendSize, timerCallback, newRoleCallback)
  }

  override def toString() = {
    s"""RaftNode ${currentState.id} {
       |  timers : $timers
       |  cluster : $cluster
       |  currentState : $currentState
       |  log : ${log}
       |}""".stripMargin
  }

}
