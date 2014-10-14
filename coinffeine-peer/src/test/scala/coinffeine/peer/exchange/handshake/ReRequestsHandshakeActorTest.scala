package coinffeine.peer.exchange.handshake

import scala.concurrent.duration._

import akka.testkit._

import coinffeine.peer.ProtocolConstants

class ReRequestsHandshakeActorTest extends HandshakeActorTest("retries") {

  override def protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitHandshakeMessagesTimeout = 500.millis.dilated,
    refundSignatureAbortTimeout = 1.minute.dilated
  )

  "The handshake actor" should "resubmit the counterpart peer handshake" in {
    shouldForwardPeerHandshake()
    givenCounterpartPeerHandshake()
    shouldForwardPeerHandshake()
  }

  it should "request refund transaction signature after a timeout" in {
    shouldCreateDeposits()
    shouldForwardRefundSignatureRequest()
    shouldSignCounterpartRefund()
    shouldForwardRefundSignatureRequest()
  }

  it should "request it again after signing counterpart refund" in {
    shouldSignCounterpartRefund()
    shouldForwardRefundSignatureRequest()
  }

  it should "send commitment to the broker until publication" in {
    givenValidRefundSignatureResponse()
    shouldForwardCommitmentToBroker()
    shouldForwardCommitmentToBroker()
    givenCommitmentPublicationNotification()
  }
}
