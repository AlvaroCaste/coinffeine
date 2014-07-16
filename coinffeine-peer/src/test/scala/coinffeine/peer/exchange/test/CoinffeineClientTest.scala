package coinffeine.peer.exchange.test

import akka.testkit.TestProbe

import coinffeine.common.test.AkkaSpec
import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange._
import coinffeine.model.network.PeerId
import coinffeine.peer.exchange.protocol._
import coinffeine.protocol.gateway.MessageGateway.{ForwardMessage, ReceiveMessage}
import coinffeine.protocol.messages.PublicMessage

abstract class CoinffeineClientTest(systemName: String)
  extends AkkaSpec(systemName) with SampleExchange {

  val gateway = TestProbe()

  def fromBroker(message: PublicMessage) = ReceiveMessage(message, brokerId)

  protected class ValidateWithPeer(validation: PeerId => Unit) {
    def to(receiver: PeerId): Unit = validation(receiver)
  }

  def shouldForward(message: PublicMessage) =
    new ValidateWithPeer(receiver => gateway.expectMsg(ForwardMessage(message, receiver)))

  protected class ValidateAllMessagesWithPeer {
    private var messages: List[PeerId => Any] = List.empty
    def message(msg: PublicMessage): ValidateAllMessagesWithPeer = {
      messages = ((receiver: PeerId) => ForwardMessage(msg, receiver)) :: messages
      this
    }
    def to(receiver: PeerId): Unit = {
      gateway.expectMsgAllOf(messages.map(_(receiver)): _*)
    }
  }

  def shouldForwardAll = new ValidateAllMessagesWithPeer
}

object CoinffeineClientTest {

  trait Perspective {
    def exchange: Exchange[FiatCurrency]
    def participants: Both[Exchange.PeerInfo]
    def handshakingExchange = HandshakingExchange(userRole, user, counterpart, exchange)
    def runningExchange = RunningExchange(MockExchangeProtocol.DummyDeposits, handshakingExchange)
    def userRole: Role
    def user = userRole.select(participants)
    def counterpart = userRole.counterpart.select(participants)
    def counterpartConnection = userRole.counterpart.select(exchange.peerIds)
    def fromCounterpart(message: PublicMessage) = ReceiveMessage(message, counterpartConnection)
  }

  trait BuyerPerspective extends Perspective {
    val userRole = BuyerRole
  }

  trait SellerPerspective extends Perspective {
    val userRole = SellerRole
  }
}
