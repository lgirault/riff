package riff.raft
import scala.reflect.ClassTag

/**
  * The external use-cases for a node in the Raft protocol are:
  *
  * 1) adding/removing nodes
  * 2) appending data to the cluster, which should return either a new (pending) commit coords, a redirect, or a 'no known leader'
  * 3) subscribing to lifecycle events, which should return a Publisher which lets subscribers know about cluster and leadership changes
  * 4) subscribing to a commit log, which should return a Publisher which lets subscribers know when logs are appended or committed
  *
  * All of these algebras don't necessarily have to be in the same trait
  */
// https://softwaremill.com/free-tagless-compared-how-not-to-commit-to-monad-too-early/
trait RaftClient[F[_], A] {

  /**
    * This is a client's view of a raft cluster, which simply wants to write some data
    *
    * @param data the data to write
    * @return an observable of the append results as they are appended/co
    */
  final def append(data: A, theRest: A*)(implicit classTag: ClassTag[A]): F[AppendStatus] =
    append(data +: theRest.toArray)

  def append(data: Array[A]): F[AppendStatus]
}