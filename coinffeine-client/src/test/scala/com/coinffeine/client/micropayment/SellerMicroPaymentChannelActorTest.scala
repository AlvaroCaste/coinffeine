package com.coinffeine.client.micropayment

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.Props
import akka.testkit.TestProbe
import org.joda.time.DateTime
import org.scalatest.mock.MockitoSugar

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.CoinffeineClientTest.SellerPerspective
import com.coinffeine.client.exchange.PaymentDescription
import com.coinffeine.client.micropayment.MicroPaymentChannelActor.{ExchangeSuccess, StartMicroPaymentChannel}
import com.coinffeine.common.Currency.Euro
import com.coinffeine.common.exchange.{Exchange, MockExchangeProtocol, MockMicroPaymentChannel, PeerId}
import com.coinffeine.common.exchange.MicroPaymentChannel.IntermediateStep
import com.coinffeine.common.paymentprocessor.Payment
import com.coinffeine.common.paymentprocessor.PaymentProcessor.{FindPayment, PaymentFound}
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.brokerage.{Market, PeerOrderRequests}
import com.coinffeine.common.protocol.messages.exchange._

class SellerMicroPaymentChannelActorTest extends CoinffeineClientTest("sellerExchange")
  with SellerPerspective with MockitoSugar {

  val listener = TestProbe()
  val paymentProcessor = TestProbe()
  val protocolConstants = ProtocolConstants(
    commitmentConfirmations = 1,
    resubmitRefundSignatureTimeout = 1 second,
    refundSignatureAbortTimeout = 1 minute)
  val channel = new MockMicroPaymentChannel(runningExchange)
  val firstStep = IntermediateStep(1, exchange.amounts.breakdown)
  val actor = system.actorOf(
    Props(new SellerMicroPaymentChannelActor(new MockExchangeProtocol())), "seller-exchange-actor")
  listener.watch(actor)

  actor ! StartMicroPaymentChannel(
    runningExchange, protocolConstants, paymentProcessor.ref, gateway.ref, Set(listener.ref))

  "The seller exchange actor" should "subscribe to the relevant messages" in {
    val Subscribe(filter) = gateway.expectMsgClass(classOf[Subscribe])
    val anotherPeer = PeerId("some-random-peer")
    val relevantPayment = PaymentProof(exchange.id, null)
    val irrelevantPayment = PaymentProof(Exchange.Id("another-id"), null)
    filter(fromCounterpart(relevantPayment)) should be (true)
    filter(ReceiveMessage(relevantPayment, anotherPeer)) should be (false)
    filter(fromCounterpart(irrelevantPayment)) should be (false)
    val randomMessage = PeerOrderRequests.empty(Market(Euro))
    filter(fromCounterpart(randomMessage)) should be (false)
  }

  it should "send the first step signature as soon as the exchange starts" in {
    val signatures = StepSignatures(exchange.id, 1, MockExchangeProtocol.DummySignatures)
    shouldForward(signatures) to counterpartConnection
  }

  it should "not send the second step signature until payment proof has been provided" in {
    gateway.expectNoMsg(100 milliseconds)
  }

  it should "send the second step signature once payment proof has been provided" in {
    actor ! fromCounterpart(PaymentProof(exchange.id, "PROOF!"))
    expectPayment(firstStep)
    val signatures = StepSignatures(exchange.id, 2, MockExchangeProtocol.DummySignatures)
    shouldForward(signatures) to counterpartConnection
  }

  it should "send step signatures as new payment proofs are provided" in {
    actor ! fromCounterpart(PaymentProof(exchange.id, "PROOF!"))
    expectPayment(IntermediateStep(2, exchange.amounts.breakdown))
    for (i <- 3 to exchange.amounts.breakdown.intermediateSteps) {
      val step = IntermediateStep(i, exchange.amounts.breakdown)
      actor ! fromCounterpart(PaymentProof(exchange.id, "PROOF!"))
      expectPayment(step)
      val signatures = StepSignatures(exchange.id, i, MockExchangeProtocol.DummySignatures)
      shouldForward(signatures) to counterpartConnection
    }
  }

  it should "send the final signature" in {
    val signatures = StepSignatures(
      exchange.id, exchange.amounts.breakdown.totalSteps, MockExchangeProtocol.DummySignatures
    )
    shouldForward(signatures) to counterpartConnection
  }

  it should "send a notification to the listeners once the exchange has finished" in {
    listener.expectMsg(ExchangeSuccess(None))
  }

  private def expectPayment(step: IntermediateStep): Unit = {
    val FindPayment(paymentId) = paymentProcessor.expectMsgClass(classOf[FindPayment])
    paymentProcessor.reply(PaymentFound(Payment(
      id = paymentId,
      senderId = participants.buyer.paymentProcessorAccount,
      receiverId = participants.seller.paymentProcessorAccount,
      description = PaymentDescription(exchange.id, step),
      amount = exchange.amounts.stepFiatAmount,
      date = DateTime.now()
    )))
  }
}
