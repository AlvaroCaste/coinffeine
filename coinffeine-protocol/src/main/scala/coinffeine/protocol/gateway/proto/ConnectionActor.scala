package coinffeine.protocol.gateway.proto

import akka.actor.{Status, Stash, Actor, ActorLogging}
import akka.pattern._

import coinffeine.model.network.PeerId
import coinffeine.protocol.gateway.p2p.P2PNetwork

class ConnectionActor(session: P2PNetwork.Session, receiverId: PeerId)
  extends Actor with Stash with ActorLogging {

  import context.dispatcher
  import ConnectionActor._

  private var connection: Option[P2PNetwork.Connection] = None

  override def preStart(): Unit = {
    startConnecting()
  }

  override def postStop(): Unit = {
    closeConnection()
  }

  override def receive: Receive = connecting

  private def connecting: Receive = {
    case conn: P2PNetwork.Connection =>
      connection = Some(conn)
      context.become(ready)
      unstashAll()
    case _ => stash()
  }

  private def ready: Receive = {
    case Message(bytes) =>
      connection.get.send(bytes)
        .map(_ => MessageDelivered)
        .pipeTo(self)
      context.become(sending)
  }

  private def sending: Receive = {
    case MessageDelivered =>
      context.become(ready)
      unstashAll()

    case Status.Failure(cause) =>
      closeConnection()
      startConnecting()
      context.become(connecting)

    case _ => stash()
  }

  private def startConnecting(): Unit = {
    session.connect(receiverId).pipeTo(self)
  }

  private def closeConnection(): Unit = {
    connection.foreach(_.close())
  }
}

object ConnectionActor {
  case class Message(bytes: Array[Byte])
  private case object MessageDelivered
}
