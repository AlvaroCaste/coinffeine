package com.coinffeine.client.exchange

import akka.actor.{Stash, ActorRef, ActorLogging, Actor}

import com.coinffeine.client.{ExchangeInfo, MessageForwarding}
import com.coinffeine.client.exchange.ExchangeActor.ExchangeSuccess
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.exchange._

/** This actor implements the seller's's side of the exchange. You can find more information about
  * the algorithm at https://github.com/Coinffeine/coinffeine/wiki/Exchange-algorithm
  */
class SellerExchangeActor(
    override protected val exchangeInfo: ExchangeInfo,
    exchange: Exchange,
    override protected val messageGateway: ActorRef,
    constants: ProtocolConstants,
    listeners: Seq[ActorRef]) extends Actor with ActorLogging with MessageForwarding with Stash {

  override def preStart(): Unit = {
    messageGateway ! Subscribe {
      case ReceiveMessage(NewOffer(exchangeInfo.`id`, _), exchangeInfo.`counterpart`) => true
      case ReceiveMessage(PaymentProof(exchangeInfo.`id`, _), exchangeInfo.`counterpart`) => true
      case _ => false
    }
    log.info(s"Exchange ${exchangeInfo.id}: Exchange started")
  }

  override def receive: Receive = waitForOffer(1)

  private def waitForOffer(step: Int): Receive = {
    case ReceiveMessage(NewOffer(_, offer), _) if exchange.getOffer(step) == offer =>
      assert(step <= exchangeInfo.steps,
        s"Invalid step $step (exchange has ${exchangeInfo.steps} steps)")
      forwardToCounterpart(OfferAccepted(exchangeInfo.id, exchange.sign(offer, exchangeInfo.userKey)))
      context.become(waitForPaymentProof(step))
    case ReceiveMessage(NewOffer(_, offer), _) =>
      log.warning(s"Unexpected new offer received: $offer. Expected: ${exchange.getOffer(step)}")
  }

  private val waitForFinalOffer: Receive = {
    case ReceiveMessage(NewOffer(_, offer), _) if exchange.finalOffer == offer =>
      forwardToCounterpart(OfferAccepted(exchangeInfo.id, exchange.sign(offer, exchangeInfo.userKey)))
      finishExchange()
  }

  private def waitForPaymentProof(step: Int): Receive = {
    case ReceiveMessage(PaymentProof(_, paymentId), _)
      if exchange.validatePayment(step, paymentId) =>
        unstashAll()
        if (step == exchangeInfo.steps) context.become(waitForFinalOffer)
        else context.become(waitForOffer(step + 1))
    case ReceiveMessage(PaymentProof(_, paymentId), _) =>
      log.warning("PaymentProof message received with invalid payment. " +
        s"Step: $step, PaymentId: $paymentId ")
    case ReceiveMessage(offer: NewOffer, _) =>
      stash()
  }

  private def finishExchange(): Unit = {
    log.info(s"Exchange ${exchangeInfo.id}: exchange finished with success")
    listeners.foreach { _ ! ExchangeSuccess }
    context.stop(self)
  }
}
