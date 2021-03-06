package riff.raft.node
import riff.raft.NodeId
import riff.raft.messages.RaftMessage

/**
  * This interface represents a stateful black-box of a raft node.
  *
  * It is typically NOT THREAD SAFE, but rather something which simply something which can take inputs and produce outputs
  *
  * @tparam NodeKey the type of the nodes in the raft cluster as viewed from a single node. This may be simple string keys, or full RESTful clients, etc.
  * @tparam A the type of data which is appended to the log (could just be a byte array, some union type, etc)
  */
trait RaftMessageHandler[A] {

  type Result = RaftNodeResult[A]

  /** @return the ID of this node in the cluster
    */
  def nodeId: NodeId

  /**
    *
    * @param from the node from which this message is received
    * @param msg the Raft message
    * @return the response
    */
  def onMessage(input: RaftMessage[A]): Result
}