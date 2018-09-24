package riff.raft.log
import riff.raft.LogIndex

import scala.util.control.NoStackTrace


sealed trait LogAppendResult
object LogAppendResult {
  def apply(firstIndex: LogIndex, lastIndex: LogIndex, replacedIndices: Seq[LogIndex] = Nil) = {
    LogAppendSuccess(firstIndex, lastIndex, replacedIndices)
  }
}

/**
  * Represents the return type of a file-based raft log
  *
  * @param written
  * @param replaced
  */
final case class LogAppendSuccess(firstIndex: LogIndex, lastIndex: LogIndex, replacedIndices: Seq[LogIndex] = Nil) extends LogAppendResult

final case class AttemptToSkipLogEntry(attemptedLogEntry : LogCoords, expectedNextIndex : LogIndex) extends Exception(s"Attempt to skip a log entry by appending ${attemptedLogEntry.index} w/ term ${attemptedLogEntry.term} when the next expected entry should've been $expectedNextIndex") with LogAppendResult with NoStackTrace
//final case class AttemptToAppendEntryWithEarlierTerm(attemptedAppend :LogCoords, latestLogEntry : LogCoords) extends Exception(s"Attempt to append an entry ${attemptedAppend} which has a term greater that our latest log entry w/ $latestLogEntry")
final case class AttemptToAppendLogEntryAtEarlierTerm(attemptedEntry : LogCoords, latestLogEntryAppended : LogCoords) extends Exception(
  s"An attempt to append ${attemptedEntry.index} w/ term ${attemptedEntry.term} when our latest entry was $latestLogEntryAppended. If an election took place after we were the leader, the term should've been incremented") with LogAppendResult with NoStackTrace