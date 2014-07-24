package coinffeine.protocol.gateway.proto

import java.net.NetworkInterface
import scala.concurrent.duration._

import akka.actor._
import akka.util.Timeout

import coinffeine.model.bitcoin.NetworkComponent
import coinffeine.model.network.PeerId
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.gateway._
import coinffeine.protocol.gateway.proto.ProtoMessageGateway.ReceiveProtoMessage
import coinffeine.protocol.gateway.proto.ProtobufServerActor.SendMessage
import coinffeine.protocol.messages.PublicMessage
import coinffeine.protocol.protobuf.{CoinffeineProtobuf => proto}
import coinffeine.protocol.serialization.{ProtocolSerialization, ProtocolSerializationComponent}

private class ProtoMessageGateway(serialization: ProtocolSerialization,
                                  ignoredNetworkInterfaces: Seq[NetworkInterface])
  extends Actor with ActorLogging {

  implicit private val timeout = Timeout(10.seconds)

  private val subscriptions = context.actorOf(SubscriptionManagerActor.props, "subscriptions")
  private val server = context.actorOf(
    ProtobufServerActor.props(ignoredNetworkInterfaces), "server")

  override def receive = waitingForInitialization orElse managingSubscriptions

  private val managingSubscriptions: Receive = {
    case msg: Subscribe =>
      context.watch(sender())
      subscriptions forward msg
    case msg @ Unsubscribe => subscriptions forward msg
    case Terminated(actor) => subscriptions.tell(Unsubscribe, actor)
  }

  private val waitingForInitialization: Receive = {
    case msg @ (Bind(_) | Connect(_, _)) =>
      server ! msg
      context.become(starting(sender()) orElse managingSubscriptions)
  }

  private def starting(listener: ActorRef): Receive = {
    case response @ (Bound(_) | Connected(_, _)) =>
      listener ! response
      log.info(s"Message gateway started")
      context.become(forwardingMessages orElse managingSubscriptions)

    case error @ (BindingError(_) | ConnectingError(_)) =>
      log.info(s"Message gateway couldn't start")
      listener ! error
      context.become(receive)
  }

  private val forwardingMessages: Receive = {
    case m @ ForwardMessage(msg, destId) =>
      log.debug(s"Forwarding message $msg to $destId")
      forward(destId, msg)

    case ReceiveProtoMessage(protoMessage, senderId) =>
      val message = serialization.fromProtobuf(protoMessage)
      subscriptions ! ReceiveMessage(message, senderId)
  }

  private def forward(to: PeerId, message: PublicMessage): Unit =
    server ! SendMessage(to, serialization.toProtobuf(message))
}

object ProtoMessageGateway {

  case class ReceiveProtoMessage(message: proto.CoinffeineMessage, senderId: PeerId)

  trait Component extends MessageGateway.Component {
    this: ProtocolSerializationComponent with NetworkComponent=>

    override def messageGatewayProps(ignoredNetworkInterfaceNames: Seq[String] = Seq.empty) = Props(
      new ProtoMessageGateway(protocolSerialization,
        networkInterfaces(ignoredNetworkInterfaceNames)))

    private def networkInterfaces(interfaceNames: Seq[String]): Seq[NetworkInterface] =
      interfaceNames.flatMap(iface => Option(NetworkInterface.getByName(iface))).toSeq
  }
}
