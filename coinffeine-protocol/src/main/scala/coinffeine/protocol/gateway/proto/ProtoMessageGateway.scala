package coinffeine.protocol.gateway.proto

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

private class ProtoMessageGateway(serialization: ProtocolSerialization)
  extends Actor with ActorLogging {

  implicit private val timeout = Timeout(10.seconds)

  private val subscriptions = context.actorOf(SubscriptionManagerActor.props, "subscriptions")
  private val server = context.actorOf(ProtobufServerActor.props, "server")

  override def receive = waitingForInitialization orElse managingSubscriptions

  private val managingSubscriptions: Receive = {
    case msg: Subscribe =>
      context.watch(sender())
      subscriptions forward msg
    case msg @ Unsubscribe => subscriptions forward msg
    case Terminated(actor) => subscriptions.tell(Unsubscribe, actor)
  }

  private val waitingForInitialization: Receive = {
    case bind: Bind =>
      server ! bind
      context.become(binding(bind, sender()) orElse managingSubscriptions)
  }

  private def binding(bind: Bind, listener: ActorRef): Receive = {
    case boundTo: Bound =>
      listener ! boundTo
      new StartedGateway(bind).start()

    case bindingError: BindingError =>
      log.info(s"Message gateway couldn't start")
      listener ! bindingError
      context.become(receive)
  }

  private class StartedGateway(bind: Bind) {

    def start(): Unit = {
      context.become(forwardingMessages orElse managingSubscriptions)
      log.info(s"Message gateway started")
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
}

object ProtoMessageGateway {

  case class ReceiveProtoMessage(message: proto.CoinffeineMessage, senderId: PeerId)

  trait Component extends MessageGateway.Component {
    this: ProtocolSerializationComponent with NetworkComponent=>

    override lazy val messageGatewayProps = Props(new ProtoMessageGateway(protocolSerialization))
  }
}
