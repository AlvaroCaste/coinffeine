package coinffeine.peer.api.impl

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

import akka.actor.ActorRef
import akka.pattern._
import org.slf4j.LoggerFactory

import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.CurrencyAmount
import coinffeine.model.payment.PaymentProcessor.AccountId
import coinffeine.peer.api.CoinffeinePaymentProcessor
import coinffeine.peer.payment.PaymentProcessorActor.{BalanceRetrieved, RetrieveBalance}

private[impl] class DefaultCoinffeinePaymentProcessor(override val accountId: AccountId,
                                                      override val peer: ActorRef)
  extends CoinffeinePaymentProcessor with PeerActorWrapper {

  override def currentBalance(): Option[CurrencyAmount[Euro.type]] =
    await((peer ? RetrieveBalance(Euro)).mapTo[BalanceRetrieved[Euro.type]]
      .map(message => Some(message.balance))
      .recover { case NonFatal(cause) =>
        DefaultCoinffeinePaymentProcessor.Log.error("Cannot retrieve current balance", cause)
        None
      })
}

private object DefaultCoinffeinePaymentProcessor {
  val Log = LoggerFactory.getLogger(classOf[DefaultCoinffeinePaymentProcessor])
}
