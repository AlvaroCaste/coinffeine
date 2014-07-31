package coinffeine.peer.exchange.test

import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.bitcoin.{KeyPair, PublicKey}
import coinffeine.model.currency.Implicits._
import coinffeine.model.exchange._
import coinffeine.model.network.PeerId
import coinffeine.peer.exchange.protocol._

trait SampleExchange extends CoinffeineUnitTestNetwork.Component {

  val brokerId = PeerId("broker")

  val participants = Both(
    buyer = Exchange.PeerInfo(
      paymentProcessorAccount = "buyer",
      bitcoinKey = new PublicKey()
    ),
    seller = Exchange.PeerInfo(
      paymentProcessorAccount = "seller",
      bitcoinKey = new KeyPair()
    )
  )

  val peerIds = Both(buyer = PeerId("buyer"), seller = PeerId("seller"))

  val amounts = Exchange.Amounts(
    bitcoinAmount = 10.BTC,
    fiatAmount = 10.EUR,
    breakdown = Exchange.StepBreakdown(intermediateSteps = 10)
  )

  val exchangeId = ExchangeId("id")

  val parameters = Exchange.Parameters(lockTime = 25, network)

  val buyerExchange =
    NonStartedExchange(exchangeId, BuyerRole, peerIds.seller, amounts, parameters, brokerId)
  val buyerRunningExchange = RunningExchange(
    MockExchangeProtocol.DummyDeposits,
    HandshakingExchange(participants.buyer, participants.seller, buyerExchange)
  )

  val sellerExchange =
    NonStartedExchange(exchangeId, SellerRole, peerIds.buyer, amounts, parameters, brokerId)
  val sellerRunningExchange = RunningExchange(
    MockExchangeProtocol.DummyDeposits,
    HandshakingExchange(participants.seller, participants.buyer, sellerExchange)
  )
}
