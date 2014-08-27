package coinffeine.protocol.gateway

import scala.concurrent.duration.{Duration, FiniteDuration}

import akka.actor.ActorDSL._
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.Assertions

import coinffeine.model.network.{BrokerId, NodeId, PeerId}
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.gateway.MockGateway.Relay
import coinffeine.protocol.messages.PublicMessage

/** MessageGateway mock to ease testing of actors communicating with other nodes. */
class MockGateway(brokerId: PeerId)(implicit system: ActorSystem) extends Assertions {

  /** Underlying probe used for poking actors. */ // TODO: rename
  private val messagesProbe = TestProbe()
  private val subscriptionsProbe = TestProbe()

  /** Actor managing subscriptions */
  val ref = actor(new Act {
    /** Mapping of subscriptions used to relay only what is subscribed or fail otherwise. */
    var subscriptions: Map[ActorRef, Set[ReceiveFilter]] = Map.empty.withDefaultValue(Set.empty)

    become {
      case msg @ Subscribe(filter) => subscribe(sender(), filter)
      case msg @ Unsubscribe => subscriptionsProbe.ref forward msg
      case Relay(msg) => relayMessage(msg)
      case other => messagesProbe.ref forward other
    }

    def subscribe(ref: ActorRef, filter: ReceiveFilter): Unit = {
      subscriptions = subscriptions.updated(sender(), subscriptions(sender()) + filter)
      subscriptionsProbe.ref forward Subscribe(filter)
    }

    def relayMessage(notification: ReceiveMessage[_ <: PublicMessage]): Unit = {
      for {
        (ref, filters) <- subscriptions.toSet
        filter <- filters if filter.isDefinedAt(notification)
      } yield {
        ref ! notification
      }
    }
  })

  def expectSubscription(
      timeout: Duration = messagesProbe.testKitSettings.DefaultTimeout.duration): Subscribe = {
    subscriptionsProbe.expectMsgPF(timeout, hint = "expected subscription") {
      case s: Subscribe => s
    }
  }

  def expectUnsubscription(
      timeout: FiniteDuration = messagesProbe.testKitSettings.DefaultTimeout.duration): Unsubscribe.type = {
    subscriptionsProbe.expectMsg(timeout, hint = "expected unsubscription to broker", Unsubscribe)
  }

  def expectForwardingToBroker(payload: PublicMessage,
                               timeout: Duration = Duration.Undefined): Unit = {
    expectForwarding(payload, brokerId, timeout)
  }

  def expectForwarding(payload: PublicMessage,
                       dest: NodeId,
                       timeout: Duration = Duration.Undefined): Unit = {
    messagesProbe.expectMsgPF(timeout) {
      case message@ForwardMessage(`payload`, `dest`) => message
      case message@ForwardMessage(`payload`, BrokerId) if isBroker(dest) => message
    }
  }

  def expectForwardingPF[T](dest: NodeId, timeout: Duration = Duration.Undefined)
                           (payloadMatcher: PartialFunction[PublicMessage, T]): T =
    messagesProbe.expectMsgPF(timeout) {
      case ForwardMessage(payload, `dest`) if payloadMatcher.isDefinedAt(payload) =>
        payloadMatcher.apply(payload)
      case ForwardMessage(payload, BrokerId)
          if isBroker(dest) && payloadMatcher.isDefinedAt(payload) =>
        payloadMatcher.apply(payload)
    }

  def expectNoMsg(timeout: FiniteDuration = messagesProbe.testKitSettings.DefaultTimeout.duration): Unit = {
    messagesProbe.expectNoMsg(timeout)
  }

  private def isBroker(nodeId: NodeId): Boolean = nodeId == BrokerId || nodeId == brokerId

  /** Relay a message to subscribed actors or make the test fail if none is subscribed. */
  def relayMessage(message: PublicMessage, origin: NodeId): Unit = {
    ref ! Relay(ReceiveMessage(message, origin))
  }

  def relayMessageFromBroker(message: PublicMessage): Unit = relayMessage(message, BrokerId)
}

private object MockGateway {
  case class Relay(receive: ReceiveMessage[_ <: PublicMessage])
}
