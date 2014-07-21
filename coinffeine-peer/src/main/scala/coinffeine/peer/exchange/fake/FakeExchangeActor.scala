package coinffeine.peer.exchange.fake

import scala.concurrent.duration._

import akka.actor._

import coinffeine.model.currency.FiatCurrency
import coinffeine.model.exchange.Exchange
import coinffeine.model.exchange.Exchange.Progress
import coinffeine.peer.exchange.ExchangeActor

/** Performs a fake exchange for demo purposes */
class FakeExchangeActor extends Actor with ActorLogging {

  override def receive: Receive = {
    case init: ExchangeActor.StartExchange =>
      new InitializedExchange(init, sender()).start()
  }

  private class InitializedExchange(init: ExchangeActor.StartExchange, listener: ActorRef) {
    import init._

    def start(): Unit = {
      logEvent("Handshake success")
      context.setReceiveTimeout(2.seconds)
      reportProgress(0)
      context.become(handlingStep(0))
    }

    def handlingStep(step: Int): Receive = {
      case ReceiveTimeout if step == exchange.amounts.breakdown.totalSteps =>
        logEvent(s"Exchange finished successfully")
        listener ! ExchangeActor.ExchangeSuccess
        context.become(finished)

      case ReceiveTimeout =>
        logEvent(s"Step $step finished")
        reportProgress(step)
        context.become(handlingStep(step + 1))
    }

    val finished: Receive = {
      case _ => // Do nothing
    }

    private def reportProgress(step: Int): Unit = {
      listener ! ExchangeActor.ExchangeProgress(new Exchange[FiatCurrency] {
        override val id = exchange.id
        override val peerIds = exchange.peerIds
        override val parameters = exchange.parameters
        override val brokerId = exchange.brokerId
        override val amounts = exchange.amounts
        override val progress = Progress[FiatCurrency](
          bitcoinsTransferred = exchange.amounts.stepBitcoinAmount * step,
          fiatTransferred = exchange.amounts.stepFiatAmount * step
        )
      })
    }

    private def logEvent(message: String): Unit = {
      log.info("{}: {}", exchange.id, message)
    }
  }
}

object FakeExchangeActor {
  trait Component extends ExchangeActor.Component {
    override def exchangeActorProps: Props = Props(new FakeExchangeActor())
  }
}
