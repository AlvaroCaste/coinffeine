package coinffeine.protocol.gateway

import java.net.NetworkInterface

import scala.concurrent.duration.FiniteDuration

import akka.actor.Props

import coinffeine.model.network.{PeerId, BrokerId, NodeId}
import coinffeine.protocol.MessageGatewaySettings
import coinffeine.protocol.messages.PublicMessage

object MessageGateway {

  case class BrokerAddress(hostname: String, port: Int) {
    override def toString = s"$hostname:$port"
  }

  sealed trait Join { // FIXME: change join messages
    val id: PeerId
    val brokerAddress: BrokerAddress
  }

  /** A request message to bind & create a new, empty P2P network. */
  case class JoinAsBroker(id: PeerId, brokerAddress: BrokerAddress) extends Join

  /** A request message to join to an already existing network. */
  case class JoinAsPeer(id: PeerId, localPort: Int, brokerAddress: BrokerAddress) extends Join

  /** A message sent in order to forward a message to a given destination. */
  case class ForwardMessage[M <: PublicMessage](message: M, dest: NodeId)

  type ReceiveFilter = PartialFunction[ReceiveMessage[_ <: PublicMessage], Unit]
  type MessageFilter = PartialFunction[PublicMessage, Unit]

  /** A message sent in order to subscribe for incoming messages.
    *
    * Each actor can only have one active subscription at a time. A second Subscribe message
    * sent to the gateway would overwrite any previous subscription.
    *
    * @param filter A filter function that indicates what messages are forwarded to the sender actor
    */
  case class Subscribe(filter: ReceiveFilter)

  object Subscribe {

    /** Create a [[Subscribe]] message for messages from the broker peer. */
    def fromBroker(filter: MessageFilter): Subscribe = Subscribe {
      case ReceiveMessage(msg, BrokerId) if filter.isDefinedAt(msg) =>
    }
  }

  /** A message sent in order to unsubscribe from incoming message reception. */
  case object Unsubscribe

  /** A message send back to the subscriber. */
  case class ReceiveMessage[M <: PublicMessage](msg: M, sender: NodeId)

  /** An exception thrown when an error is found on message forward. */
  case class ForwardException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

  trait Component {

    def messageGatewayProps(settings: MessageGatewaySettings): Props =
      messageGatewayProps(settings.ignoredNetworkInterfaces, settings.connectionRetryInterval)

    def messageGatewayProps(ignoredNetworkInterfaces: Seq[NetworkInterface],
                            connectionRetryInterval: FiniteDuration): Props
  }
}
