package riff.raft.node
import java.nio.file.Path

import eie.io._
import riff.raft.Term

object NIOPersistentState {

  def apply(dir: Path, createDirIfNotExists: Boolean): PersistentState = {
    val dirOk = dir.isDir || (createDirIfNotExists && dir.mkDirs().isDir)
    require(dirOk, s"${dir} is not a directory")
    new NIOPersistentState(dir).cached()
  }
}

/**
  * Some [[PersistentState]] written to the file system using java.nio
  *
  * @param dir
  * @param ev$1
  * @param ev$2
  * @tparam NodeKey
  */
class NIOPersistentState(dir: Path) extends PersistentState {

  private val currentTermFile = {
    val path = dir.resolve(".currentTerm")
    if (!path.exists()) {
      path.text = 0.toString
    }
    path
  }

  override def currentTerm: Term = currentTermFile.text.toInt

  override def currentTerm_=(term: Term) = {
    val current = currentTerm
    require(term > current, s"attempt to decrement term from $current to $term")
    currentTermFile.text = term.toString
    this
  }

  private def votedForFile(term: Term) = dir.resolve(s"${term}.votedFor")

  override def votedFor(term: Term): Option[String] = {
    val path = votedForFile(term)
    if (path.exists()) {
      FromBytes[String].read(path.bytes).toOption
    } else {
      None
    }
  }

  override def castVote(term: Term, node: String) = {
    val alreadyVoted = votedFor(term)
    require(alreadyVoted.isEmpty, s"Already voted in term $term for $alreadyVoted")
    votedForFile(term).bytes = ToBytes[String].bytes(node)
  }

}
