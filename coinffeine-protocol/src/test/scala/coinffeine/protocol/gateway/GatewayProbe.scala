package coinffeine.protocol.gateway

import scala.concurrent.duration.{Duration, FiniteDuration}

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe
import org.scalatest.Assertions

import coinffeine.model.network.PeerId
import coinffeine.protocol.gateway.MessageGateway._
import coinffeine.protocol.messages.PublicMessage

/** Probe specialized on mocking a MessageGateway. */
class GatewayProbe(implicit system: ActorSystem) extends Assertions {

  /** Underlying probe used for poking actors. */
  private val probe = TestProbe()

  /** Mapping of subscriptions used to relay only what is subscribed or fail otherwise. */
  private var subscriptions: Map[ActorRef, Set[Filter]] = Map.empty

  def ref = probe.ref

  def expectSubscription(): Subscribe =
    expectSubscription(probe.testKitSettings.DefaultTimeout.duration)

  def expectSubscription(timeout: Duration): Subscribe = {
    val subscription = try {
      probe.expectMsgPF(timeout) {
        case s: Subscribe => s
      }
    } catch {
      case ex: AssertionError => fail("Expected subscription failed", ex)
    }
    val currentSubscription = subscriptions.getOrElse(probe.sender(), Set.empty)
    subscriptions = subscriptions.updated(probe.sender(), currentSubscription + subscription.filter)
    subscription
  }

  def expectForwarding(payload: Any, dest: PeerId, timeout: Duration = Duration.Undefined): Unit =
    probe.expectMsgPF(timeout) {
      case message @ ForwardMessage(`payload`, `dest`) => message
    }

  def expectForwardingPF[T](dest: PeerId, timeout: Duration = Duration.Undefined)
                           (payloadMatcher: PartialFunction[Any, T]): T =
    probe.expectMsgPF(timeout) {
      case ForwardMessage(payload, `dest`) if payloadMatcher.isDefinedAt(payload) =>
        payloadMatcher.apply(payload)
    }

  def expectNoMsg(): Unit = probe.expectNoMsg()

  def expectNoMsg(timeout: FiniteDuration): Unit = probe.expectNoMsg(timeout)

  /** Relay a message to subscribed actors or make the test fail if none is subscribed. */
  def relayMessage(message: PublicMessage, origin: PeerId): Unit = {
    val notification = ReceiveMessage(message, origin)
    val targets = for {
      (ref, filters) <- subscriptions.toSet
      filter <- filters
      if filter.isDefinedAt(notification)
    } yield ref
    assert(targets.nonEmpty, s"No one is expecting $notification, check subscription filters")
    targets.foreach { target =>
      probe.send(target, notification)
    }
  }
}
