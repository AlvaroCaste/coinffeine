package coinffeine.peer.market.orders

import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe

import coinffeine.common.akka.test.{AkkaSpec, MockSupervisedActor}
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.bitcoin.{BlockedCoinsId, KeyPair}
import coinffeine.model.currency.Implicits._
import coinffeine.model.currency._
import coinffeine.model.event._
import coinffeine.model.exchange.Exchange.Amounts
import coinffeine.model.exchange._
import coinffeine.model.market._
import coinffeine.model.network.PeerId
import coinffeine.model.payment.PaymentProcessor.BlockedFundsId
import coinffeine.peer.amounts.AmountsCalculator
import coinffeine.peer.bitcoin.WalletActor
import coinffeine.peer.exchange.ExchangeActor
import coinffeine.peer.exchange.test.CoinffeineClientTest.{BuyerPerspective, Perspective, SellerPerspective}
import coinffeine.peer.market.SubmissionSupervisor.{InMarket, KeepSubmitting, StopSubmitting}
import coinffeine.peer.market.orders.OrderActor.{BlockingFundsMessage, NoFundsMessage}
import coinffeine.peer.payment.PaymentProcessorActor
import coinffeine.protocol.gateway.MockGateway
import coinffeine.protocol.messages.brokerage.OrderMatch
import coinffeine.protocol.messages.handshake.ExchangeRejection

class OrderActorTest extends AkkaSpec {

  val idleTime = 500.millis

  "A bidding order actor" should "keep order info" in new BuyerFixture {
    actor ! OrderActor.RetrieveStatus
    expectMsg(blockingFundsOrder)
  }

  it should "block FIAT funds plus fees when initialized" in new BuyerFixture {
    givenInitializedOrder()
  }

  it should "keep in stalled status when there are not enough funds when buying" in
    new BuyerFixture {
      givenInitializedOrder()
      givenFundsBecomeUnavailable()
      eventChannelProbe.expectNoMsg(idleTime)
      submissionProbe.expectNoMsg(idleTime)
    }

  it should "move to stalled when payment processor reports unavailable funds" in
    new BuyerFixture {
      givenOfflineOrder()
      givenFundsBecomeUnavailable()
      eventChannelProbe.expectMsg(OrderStatusChangedEvent(
        order.id, OfflineOrder, StalledOrder(NoFundsMessage)))
      submissionProbe.expectMsg(StopSubmitting(order.id))
    }

  it should "reject order matches when stalled" in new BuyerFixture {
    givenOfflineOrder()
    givenFundsBecomeUnavailable()
    shouldRejectAnOrderMatch("Order is stalled")
  }

  it should "move to offline when receive available funds" in new BuyerFixture {
    givenStalledOrder()
    givenFundsBecomeAvailable()
    eventChannelProbe.expectMsg(OrderStatusChangedEvent(
      order.id, StalledOrder(NoFundsMessage), OfflineOrder))
    submissionProbe.expectMsg(KeepSubmitting(OrderBookEntry(order)))
  }

  it should "submit to the broker and receive submission status" in new BuyerFixture {
    givenOfflineOrder()
    val entry = OrderBookEntry(order)
    submissionProbe.send(actor, InMarket(entry))
    eventChannelProbe.expectMsgPF() {
      case OrderStatusChangedEvent(orderId, _, InMarketOrder) if orderId == order.id =>
    }
  }

  it should "keep submitting to the broker until been cancelled" in new BuyerFixture {
    givenOfflineOrder()
    expectNoMsg(idleTime)
    val reason = "some reason"
    actor ! OrderActor.CancelOrder(reason)
    submissionProbe.expectMsg(StopSubmitting(order.id))
    eventChannelProbe.expectMsgPF() {
      case OrderStatusChangedEvent(orderId, _, CancelledOrder(`reason`)) if orderId == order.id =>
    }
  }

  it should "reject order matches after being cancelled" in new BuyerFixture {
    givenOfflineOrder()
    actor ! OrderActor.CancelOrder("got bored")
    shouldRejectAnOrderMatch("Already finished")
  }

  it should "release funds when being cancelled" in new BuyerFixture {
    givenOfflineOrder()
    actor ! OrderActor.CancelOrder("testing purposes")
    fundsActor.expectMsg(OrderFundsActor.UnblockFunds)
  }

  it should "move from stalled to offline when available funds message is received" in
    new BuyerFixture {
      givenStalledOrder()
      givenFundsBecomeAvailable()
      eventChannelProbe.expectMsg(OrderStatusChangedEvent(
        order.id, StalledOrder(NoFundsMessage), OfflineOrder))
      submissionProbe.expectMsg(KeepSubmitting(OrderBookEntry(order)))
    }

  it should "stop submitting to the broker & report new status once matching is received" in
    new BuyerFixture {
      givenInMarketOrder()
      gatewayProbe.relayMessageFromBroker(orderMatch)
      submissionProbe.fishForMessage() {
        case StopSubmitting(orderId) if orderId == order.id => true
        case _ => false
      }
      eventChannelProbe.expectMsgAllOf(
        OrderStatusChangedEvent(order.id, InMarketOrder, InProgressOrder),
        OrderProgressedEvent(order.id, 0.0, 0.0)
      )
      exchangeActor.probe.send(actor, ExchangeActor.ExchangeSuccess(completedExchange))
      eventChannelProbe.fishForMessage() {
        case OrderStatusChangedEvent(orderId, _, CompletedOrder) if orderId == order.id => true
        case _ => false
      }
    }

  it should "spawn an exchange upon matching" in new BuyerFixture {
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    val keyPair = givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()
  }

  it should "reject new order matches if an exchange is active" in new BuyerFixture {
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()
    shouldRejectAnOrderMatch("Exchange already in progress")
  }

  it should "not reject resubmissions of already accepted order matches" in new BuyerFixture {
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    givenAFreshKeyIsGenerated()
    givenPaymentProcessorAccountIsRetrieved()
    exchangeActor.expectCreation()

    gatewayProbe.relayMessageFromBroker(orderMatch)
    gatewayProbe.expectNoMsg(idleTime)
  }

  it should "release remaining funds after completing exchanges" in new BuyerFixture {
    givenInMarketOrder()
    givenASuccessfulPerfectMatchExchange()
    fundsActor.expectMsg(OrderFundsActor.UnblockFunds)
  }

  "An asking order actor" should "keep order info" in new SellerFixture {
    actor ! OrderActor.RetrieveStatus
    expectMsg(blockingFundsOrder)
  }

  it should "submit to the broker and receive submission status" in new SellerFixture {
    givenInMarketOrder()
  }

  it should "keep submitting to the broker until been cancelled" in new SellerFixture {
    givenInMarketOrder()
    val reason = "some reason"
    actor ! OrderActor.CancelOrder(reason)
    submissionProbe.expectMsg(StopSubmitting(order.id))
    eventChannelProbe.expectMsgPF() {
      case OrderStatusChangedEvent(order.id, InMarketOrder, CancelledOrder(`reason`)) =>
    }
  }

  it should "stop submitting to the broker & send event once matching is received" in
    new SellerFixture {
      givenInMarketOrder()
      gatewayProbe.relayMessageFromBroker(orderMatch)
      submissionProbe.fishForMessage() {
        case StopSubmitting(orderId) if orderId == order.id => true
        case _ => false
      }
      exchangeActor.probe.send(actor, ExchangeActor.ExchangeSuccess(completedExchange))
      eventChannelProbe.fishForMessage() {
        case OrderStatusChangedEvent(orderId, _, CompletedOrder) => orderId == order.id
        case _ => false
      }
    }

  it should "spawn an exchange upon matching" in new SellerFixture {
    givenInMarketOrder()
    gatewayProbe.relayMessageFromBroker(orderMatch)
    expectAPerfectMatchExchangeToBeStarted()
  }

  trait Fixture extends SampleExchange with CoinffeineUnitTestNetwork.Component { this: Perspective =>
    val role: Role
    def fiatFunds: Option[BlockedFundsId]
    val order: Order[_ <: FiatCurrency]
    val gatewayProbe = new MockGateway(PeerId("broker"))
    val fundsActor = new MockSupervisedActor()
    val submissionProbe, paymentProcessorProbe, bitcoinPeerProbe, walletProbe = TestProbe()
    val eventChannelProbe = EventChannelProbe()
    def blockedFunds = Exchange.BlockedFunds(fiatFunds, BlockedCoinsId(1))
    val exchangeActor = new MockSupervisedActor()
    def calculator = new AmountsCalculator {
      override def exchangeAmountsFor[C <: FiatCurrency](bitcoinAmount: BitcoinAmount,
                                                         fiatAmount: CurrencyAmount[C]) =
        amounts.asInstanceOf[Amounts[C]]
    }
    val paymentProcessorId = exchange.role.select(participants).paymentProcessorAccount
    val blockingFundsOrder = order.withStatus(StalledOrder(BlockingFundsMessage))
    val offlineOrder = order.withStatus(OfflineOrder)
    val inMarketOrder = order.withStatus(InMarketOrder)
    private val collaborators = OrderActor.Collaborators(walletProbe.ref, paymentProcessorProbe.ref,
      submissionProbe.ref, gatewayProbe.ref, bitcoinPeerProbe.ref)
    val actor = system.actorOf(Props(new OrderActor(
      (_, _) => exchangeActor.props,
      fundsActor.props,
      network,
      calculator,
      order,
      collaborators
    )))

    val orderMatch = OrderMatch(
      order.id, exchangeId, order.amount, order.price.of(order.amount),
      lockTime = 400000L, exchange.counterpartId)

    gatewayProbe.expectSubscription()
    fundsActor.expectCreation()

    def givenInitializedOrder(): Unit = {
      eventChannelProbe.expectMsg(OrderSubmittedEvent(blockingFundsOrder))
      fundsActor.expectMsgType[OrderFundsActor.BlockFunds]
    }

    def givenOfflineOrder(): Unit = {
      givenInitializedOrder()
      givenFundsBecomeAvailable()
      eventChannelProbe.expectMsg(OrderStatusChangedEvent(
        order.id, StalledOrder(BlockingFundsMessage), OfflineOrder))
      submissionProbe.expectMsg(KeepSubmitting(OrderBookEntry(order)))
    }

    def givenFundsBecomeAvailable(): Unit = {
      fundsActor.probe.send(actor, OrderFundsActor.AvailableFunds(blockedFunds))
    }

    def givenFundsBecomeUnavailable(): Unit = {
      fundsActor.probe.send(actor, OrderFundsActor.UnavailableFunds)
    }

    def givenInMarketOrder(): Unit = {
      givenOfflineOrder()
      submissionProbe.send(actor, InMarket(OrderBookEntry(order)))
      eventChannelProbe.expectMsg(OrderStatusChangedEvent(order.id, OfflineOrder, InMarketOrder))
    }

    def givenAFreshKeyIsGenerated(): KeyPair = {
      val keyPair = new KeyPair()
      walletProbe.expectMsg(WalletActor.CreateKeyPair)
      walletProbe.reply(WalletActor.KeyPairCreated(keyPair))
      keyPair
    }

    def givenPaymentProcessorAccountIsRetrieved(): Unit = {
      paymentProcessorProbe.expectMsg(PaymentProcessorActor.RetrieveAccountId)
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
  }

  trait BuyerFixture extends Fixture with BuyerPerspective {
    override lazy val order = Order(Bid, 5.BTC, Price(500.EUR))
    override val role: Role = BuyerRole
    override val fiatFunds = Some(BlockedFundsId(1))

    def givenStalledOrder(): Unit = {
      givenOfflineOrder()
      givenFundsBecomeUnavailable()
      eventChannelProbe.expectMsg(OrderStatusChangedEvent(
        order.id, OfflineOrder, StalledOrder(NoFundsMessage)))
      submissionProbe.expectMsg(StopSubmitting(order.id))
    }
  }

  trait SellerFixture extends Fixture with SellerPerspective {
    override lazy val order = Order(Ask, 5.BTC, Price(500.EUR))
    override val role: Role = SellerRole
    override val fiatFunds = None
  }
}
