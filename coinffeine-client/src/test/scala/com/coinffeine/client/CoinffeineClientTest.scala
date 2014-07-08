package com.coinffeine.client

import akka.testkit.TestProbe

import com.coinffeine.common.{FiatCurrency, PeerConnection}
import com.coinffeine.common.exchange._
import com.coinffeine.common.protocol.gateway.MessageGateway.{ForwardMessage, ReceiveMessage}
import com.coinffeine.common.protocol.messages.PublicMessage
import com.coinffeine.common.test.AkkaSpec

abstract class CoinffeineClientTest(systemName: String)
  extends AkkaSpec(systemName) with SampleExchange {

  val gateway = TestProbe()

  def fromBroker(message: PublicMessage) = ReceiveMessage(message, broker)

  protected class ValidateWithPeer(validation: PeerConnection => Unit) {
    def to(receiver: PeerConnection): Unit = validation(receiver)
  }

  def shouldForward(message: PublicMessage) =
    new ValidateWithPeer(receiver => gateway.expectMsg(ForwardMessage(message, receiver)))

  protected class ValidateAllMessagesWithPeer {
    private var messages: List[PeerConnection => Any] = List.empty
    def message(msg: PublicMessage): ValidateAllMessagesWithPeer = {
      messages = ((receiver: PeerConnection) => ForwardMessage(msg, receiver)) :: messages
      this
    }
    def to(receiver: PeerConnection): Unit = {
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
    def user = participants(userRole)
    def counterpart = participants(userRole.counterpart)
    def counterpartConnection = exchange.connections(userRole.counterpart)
    def fromCounterpart(message: PublicMessage) = ReceiveMessage(message, counterpartConnection)
  }

  trait BuyerPerspective extends Perspective {
    val userRole = BuyerRole
  }

  trait SellerPerspective extends Perspective {
    val userRole = SellerRole
  }
}
