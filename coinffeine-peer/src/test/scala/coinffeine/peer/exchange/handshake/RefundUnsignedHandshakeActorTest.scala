package coinffeine.peer.exchange.handshake

import scala.concurrent.duration._

import coinffeine.protocol.gateway.MessageGateway.ForwardMessage
import coinffeine.protocol.messages.handshake.ExchangeRejection
import com.coinffeine.common.ProtocolConstants

class RefundUnsignedHandshakeActorTest extends HandshakeActorTest("signature-timeout") {

  import coinffeine.peer.exchange.handshake.HandshakeActor._

  override def protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 10 seconds,
    refundSignatureAbortTimeout = 100 millis
  )

  "Handshakes without our refund signed" should "be aborted after a timeout" in {
    givenActorIsInitialized()
    listener.expectMsgClass(classOf[HandshakeFailure])
    listener.expectTerminated(actor)
  }

  it must "notify the broker that the exchange is rejected" in {
    gateway.fishForMessage() {
      case ForwardMessage(ExchangeRejection(exchange.`id`, _), exchange.`brokerId`) => true
      case _ => false
    }
  }
}
