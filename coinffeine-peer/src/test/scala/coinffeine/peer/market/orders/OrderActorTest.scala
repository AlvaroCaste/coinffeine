package coinffeine.peer.market.orders

import scala.concurrent.duration._

import akka.actor.{ActorContext, Props}
import akka.testkit.TestProbe
import org.scalatest.Inside
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.mock.MockitoSugar

import coinffeine.common.akka.test.{AkkaSpec, MockSupervisedActor}
import coinffeine.model.bitcoin.{BlockedCoinsId, KeyPair}
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.{MutableCoinffeineNetworkProperties, PeerId}
import coinffeine.model.payment.PaymentProcessor
import coinffeine.peer.amounts.AmountsCalculatorStub
import coinffeine.peer.bitcoin.WalletActor
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.exchange.ExchangeActor.ExchangeToStart
import coinffeine.peer.exchange.test.CoinffeineClientTest.BuyerPerspective
import coinffeine.peer.market.SubmissionSupervisor.{InMarket, KeepSubmitting, StopSubmitting}
import coinffeine.peer.market.orders.OrderActor.Delegates
import coinffeine.peer.market.orders.controller.OrderController
import coinffeine.peer.market.orders.funds.FakeOrderFundsBlocker
import coinffeine.peer.payment.PaymentProcessorActor
import coinffeine.protocol.gateway.MockGateway
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake.ExchangeRejection

class OrderActorTest extends AkkaSpec
    with SampleExchange with BuyerPerspective with CoinffeineUnitTestNetwork.Component
    with Inside  with Eventually with MockitoSugar {

  val idleTime = 500.millis
  val order = Order(Bid, 10.BTC, Price(2.EUR))
  val orderMatch = OrderMatch(
    order.id,
    exchangeId,
    Both(buyer = amounts.netBitcoinExchanged, seller = amounts.grossBitcoinExchanged),
    Both(buyer = amounts.grossFiatExchanged, seller = amounts.netFiatExchanged),
    lockTime = 400000L,
    exchange.counterpartId
  )

  "An order actor" should "keep order info" in new Fixture {
    actor ! OrderActor.RetrieveStatus
    expectMsgType[Order[_]]
  }

  it should "block FIAT funds plus fees when initialized" in new Fixture {
    givenInitializedOrder()
  }

  it should "submit to the broker and receive submission status" in new Fixture {
    givenOfflineOrder()
    submissionProbe.send(actor, InMarket(entry))
    expectProperty { _.status shouldBe InMarketOrder }
  }

  it should "keep submitting to the broker until been cancelled" in new Fixture {
    givenOfflineOrder()
    expectNoMsg(idleTime)
    val reason = "some reason"
    actor ! OrderActor.CancelOrder(reason)
    submissionProbe.expectMsg(StopSubmitting(order.id))
    expectProperty { _.status should beCancelled }
  }

  it should "reject order matches after being cancelled" in new Fixture {
    givenOfflineOrder()
    actor ! OrderActor.CancelOrder("got bored")
    shouldRejectAnOrderMatch("Order already finished")
  }

  it should "stop submitting to the broker & report new status once matching is received" in
    new Fixture {
      fundsBlocking.givenSuccessfulFundsBlocking(blockedFunds)
      givenInMarketOrder()
      gatewayProbe.relayMessageFromBroker(orderMatch)
      submissionProbe.fishForMessage() {
        case StopSubmitting(orderId) if orderId == order.id => true
        case _ => false
      }
      expectProperty { _.status shouldBe InProgressOrder }
      expectProperty { _.progress shouldBe 0.0 }
      exchangeActor.probe.send(actor, ExchangeActor.ExchangeSuccess(completedExchange))
      expectProperty { _.status shouldBe CompletedOrder }
    }

  it should "spawn an exchange upon matching" in new Fixture {
    fundsBlocking.givenSuccessfulFundsBlocking(blockedFunds)
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    val keyPair = givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()
  }

  it should "reject new order matches if an exchange is active" in new Fixture {
    fundsBlocking.givenSuccessfulFundsBlocking(blockedFunds)
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()
    shouldRejectAnOrderMatch("Exchange already in progress")
  }

  it should "not reject resubmissions of already accepted order matches" in new Fixture {
    fundsBlocking.givenSuccessfulFundsBlocking(blockedFunds)
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()

    gatewayProbe.relayMessageFromBroker(orderMatch)
    gatewayProbe.expectNoMsg(idleTime)
  }

  trait Fixture {
    val gatewayProbe = new MockGateway(PeerId("broker"))
    val exchangeActor = new MockSupervisedActor()
    val submissionProbe, paymentProcessorProbe, bitcoinPeerProbe, walletProbe = TestProbe()
    val blockedFunds = Exchange.BlockedFunds(Some(PaymentProcessor.BlockedFundsId(1)), BlockedCoinsId(2))
    val fundsBlocking = new FakeOrderFundsBlocker
    val entry = OrderBookEntry.fromOrder(order)
    private val calculatorStub = new AmountsCalculatorStub(amounts)
    val properties = new MutableCoinffeineNetworkProperties
    val actor = system.actorOf(Props(new OrderActor[Euro.type](
      order,
      calculatorStub,
      (publisher, funds) =>
        new OrderController(calculatorStub, network, order, properties, publisher, funds),
      new Delegates[Euro.type] {
        override def exchangeActor(exchange: ExchangeToStart[Euro.type])
                                  (implicit context: ActorContext) =
          Fixture.this.exchangeActor.props

        override def delegatedFundsBlocking()(implicit context: ActorContext) = fundsBlocking
      },
      OrderActor.Collaborators(walletProbe.ref, paymentProcessorProbe.ref,
        submissionProbe.ref, gatewayProbe.ref, bitcoinPeerProbe.ref)
    )))

    def givenInitializedOrder(): Unit = {
      eventually { properties.orders.get(order.id) shouldBe 'defined }
    }

    def givenOfflineOrder(): Unit = {
      givenInitializedOrder()
      expectProperty { _.status shouldBe OfflineOrder }
      submissionProbe.expectMsg(KeepSubmitting(entry))
    }

    def givenInMarketOrder(): Unit = {
      givenOfflineOrder()
      submissionProbe.send(actor, InMarket(entry))
      expectProperty { _.status shouldBe InMarketOrder }
    }

    def givenAFreshKeyIsGenerated(): KeyPair = {
      val keyPair = new KeyPair()
      walletProbe.expectMsg(WalletActor.CreateKeyPair)
      walletProbe.reply(WalletActor.KeyPairCreated(keyPair))
      keyPair
    }

    def givenPaymentProcessorAccountIsRetrieved(): Unit = {
      paymentProcessorProbe.expectMsg(PaymentProcessorActor.RetrieveAccountId)
      val paymentProcessorId = participants.buyer.paymentProcessorAccount
      paymentProcessorProbe.reply(PaymentProcessorActor.RetrievedAccountId(paymentProcessorId))
    }

    def expectAPerfectMatchExchangeToBeStarted(): Unit = {
      givenAFreshKeyIsGenerated()
      givenPaymentProcessorAccountIsRetrieved()
      exchangeActor.expectCreation()
    }

    def givenASuccessfulPerfectMatchExchange(): Unit = {
      gatewayProbe.relayMessageFromBroker(orderMatch)
      expectAPerfectMatchExchangeToBeStarted()
      exchangeActor.probe.send(actor, ExchangeActor.ExchangeSuccess(completedExchange))
    }

    def shouldRejectAnOrderMatch(errorMessage: String): Unit = {
      val otherExchangeId = ExchangeId.random()
      gatewayProbe.relayMessageFromBroker(orderMatch.copy(exchangeId = otherExchangeId))
      gatewayProbe.expectForwardingToBroker(
        ExchangeRejection(otherExchangeId, errorMessage))
    }

    def expectProperty(f: AnyCurrencyOrder => Unit): Unit = {
      eventually {
        f(properties.orders(order.id))
      }
    }
  }

  val beCancelled = new Matcher[OrderStatus] {
    override def apply(left: OrderStatus) = MatchResult(
      left.isInstanceOf[CancelledOrder],
      s"$left is not cancelled",
      s"$left is cancelled"
    )
  }
}
