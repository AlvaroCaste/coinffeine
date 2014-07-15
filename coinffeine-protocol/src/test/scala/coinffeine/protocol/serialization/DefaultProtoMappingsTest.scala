package coinffeine.protocol.serialization

import java.math.BigInteger

import com.google.bitcoin.params.UnitTestParams
import com.google.protobuf.{ByteString, Message}

import coinffeine.model.bitcoin.Implicits._
import coinffeine.model.bitcoin._
import coinffeine.model.bitcoin.test.CoinffeineUnitTestNetwork
import coinffeine.model.currency.Currency.Euro
import coinffeine.model.currency.Implicits._
import coinffeine.model.currency.{BitcoinAmount, FiatAmount, FiatCurrency}
import coinffeine.model.exchange.{Both, Exchange}
import coinffeine.model.market.{Bid, OrderBookEntry, OrderId}
import coinffeine.model.network.PeerId
import coinffeine.protocol.messages.arbitration.CommitmentNotification
import coinffeine.protocol.messages.brokerage._
import coinffeine.protocol.messages.handshake._
import coinffeine.protocol.protobuf.{CoinffeineProtobuf => msg}
import com.coinffeine.common.test.UnitTest

class DefaultProtoMappingsTest extends UnitTest with CoinffeineUnitTestNetwork.Component {

  val commitmentTransaction = ImmutableTransaction(new MutableTransaction(network))
  val txSerialization = new TransactionSerialization(network)
  val testMappings = new DefaultProtoMappings(txSerialization)
  import testMappings._

  def thereIsAMappingBetween[T, M <: Message](obj: T, msg: M)
                                             (implicit mapping: ProtoMapping[T, M]): Unit = {

    it should "convert the case class into the protobuf message" in {
      ProtoMapping.toProtobuf(obj) should be (msg)
    }

    it should "convert to protobuf message to the case class" in {
      ProtoMapping.fromProtobuf(msg) should be (obj)
    }

    it should "convert to protobuf and back again" in {
      ProtoMapping.fromProtobuf(ProtoMapping.toProtobuf(obj)) should be (obj)
    }
  }

  val sampleTxId = new Hash("d03f71f44d97243a83804b227cee881280556e9e73e5110ecdcb1bbf72d75c71")
  val sampleOrderId = OrderId.random()
  val sampleExchangeId = Exchange.Id.random()

  val btcAmount = 1.1 BTC
  val btcAmountMessage = msg.BtcAmount.newBuilder
    .setValue(11)
    .setScale(1)
    .build()
  "BTC amount" should behave like thereIsAMappingBetween(btcAmount, btcAmountMessage)

  "Fiat amount" should behave like thereIsAMappingBetween(3 EUR: FiatAmount, msg.FiatAmount.newBuilder
    .setCurrency("EUR")
    .setScale(0)
    .setValue(3)
    .build
  )

  val orderBookEntry = OrderBookEntry(OrderId("orderId"), Bid, 10.BTC, 400.EUR)
  val orderBookEntryMessage = msg.OrderBookEntry.newBuilder
    .setId("orderId")
    .setOrderType(msg.OrderBookEntry.OrderType.BID)
    .setAmount(msg.BtcAmount.newBuilder.setValue(10).setScale(0))
    .setPrice(msg.FiatAmount.newBuilder.setValue(400).setScale(0).setCurrency("EUR"))
    .build

  "Order" should behave like thereIsAMappingBetween[OrderBookEntry[FiatAmount], msg.OrderBookEntry](
    orderBookEntry, orderBookEntryMessage)

  val positions = PeerOrderRequests(Market(Euro), Seq(orderBookEntry))
  val positionsMessage = msg.PeerOrderRequests.newBuilder
    .setMarket(msg.Market.newBuilder.setCurrency("EUR").build)
    .addEntries(orderBookEntryMessage)
    .build

  "Peer positions" should behave like
    thereIsAMappingBetween[PeerOrderRequests[FiatCurrency], msg.PeerOrderRequests](
      positions, positionsMessage)

  val commitmentNotification = CommitmentNotification(sampleExchangeId, Both(sampleTxId, sampleTxId))
  val commitmentNotificationMessage = msg.CommitmentNotification.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setBuyerTxId(ByteString.copyFrom(sampleTxId.getBytes))
    .setSellerTxId(ByteString.copyFrom(sampleTxId.getBytes))
    .build()

  "Commitment notification" should behave like thereIsAMappingBetween(
    commitmentNotification, commitmentNotificationMessage)

  val commitment = ExchangeCommitment(sampleExchangeId, commitmentTransaction)
  val commitmentMessage = msg.ExchangeCommitment.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setCommitmentTransaction( txSerialization.serialize(commitmentTransaction))
    .build()

  "Enter exchange" must behave like thereIsAMappingBetween(commitment, commitmentMessage)

  val exchangeAborted = ExchangeAborted(Exchange.Id(sampleExchangeId.value), "a reason")
  val exchangeAbortedMessage = msg.ExchangeAborted.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setReason("a reason")
    .build()

  "Exchange aborted" should behave like thereIsAMappingBetween(
    exchangeAborted, exchangeAbortedMessage)

  val exchangeRejection = ExchangeRejection(
    exchangeId = sampleExchangeId,
    reason = "a reason")
  val exchangeRejectionMessage = msg.ExchangeRejection.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setReason("a reason")
    .build()

  "Exchange rejection" should behave like thereIsAMappingBetween(
    exchangeRejection, exchangeRejectionMessage)

  val orderMatch = OrderMatch(
    orderId = sampleOrderId,
    exchangeId = sampleExchangeId,
    amount = 0.1 BTC,
    price = 10000 EUR,
    counterpart = PeerId("buyer")
  )
  val orderMatchMessage = msg.OrderMatch.newBuilder
    .setOrderId(sampleOrderId.value)
    .setExchangeId(sampleExchangeId.value)
    .setAmount(ProtoMapping.toProtobuf[BitcoinAmount, msg.BtcAmount](0.1 BTC))
    .setPrice(ProtoMapping.toProtobuf[FiatAmount, msg.FiatAmount](10000 EUR))
    .setCounterpart("buyer")
    .build
  "Order match" must behave like thereIsAMappingBetween(orderMatch, orderMatchMessage)

  val emptyQuoteMessage = msg.Quote.newBuilder
    .setMarket(msg.Market.newBuilder.setCurrency("EUR"))
    .build
  val emptyQuote = Quote.empty(Market(Euro))
  "Empty quote" must behave like thereIsAMappingBetween[Quote[FiatCurrency], msg.Quote](
    emptyQuote, emptyQuoteMessage)

  val quoteMessage = emptyQuoteMessage.toBuilder
    .setHighestBid(ProtoMapping.toProtobuf[FiatAmount, msg.FiatAmount](20 EUR))
    .setLowestAsk(ProtoMapping.toProtobuf[FiatAmount, msg.FiatAmount](30 EUR))
    .setLastPrice(ProtoMapping.toProtobuf[FiatAmount, msg.FiatAmount](22 EUR))
    .build
  val quote = Quote(20.EUR -> 30.EUR, 22 EUR)
  "Quote" must behave like thereIsAMappingBetween[Quote[FiatCurrency], msg.Quote](
    quote, quoteMessage)

  val quoteRequest = QuoteRequest(Market(Euro))
  val quoteRequestMessage = msg.QuoteRequest.newBuilder
    .setMarket(msg.Market.newBuilder.setCurrency("EUR"))
    .build

  "Quote request" must behave like thereIsAMappingBetween(quoteRequest, quoteRequestMessage)

  val publicKey = new KeyPair().publicKey
  val peerHandshake = PeerHandshake(sampleExchangeId, publicKey, "accountId")
  val peerHandshakeMessage = msg.PeerHandshake.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setPublicKey(ByteString.copyFrom(publicKey.getPubKey))
    .setPaymentProcessorAccount("accountId")
    .build()

  "Peer handshake" must behave like thereIsAMappingBetween(peerHandshake, peerHandshakeMessage)

  val refundTx = ImmutableTransaction(new MutableTransaction(UnitTestParams.get()))
  val refundSignatureRequest = RefundSignatureRequest(sampleExchangeId, refundTx)
  val refundSignatureRequestMessage = msg.RefundSignatureRequest.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setRefundTx(ByteString.copyFrom(refundTx.get.bitcoinSerialize()))
    .build()

  "Refund signature request" must behave like thereIsAMappingBetween(
    refundSignatureRequest, refundSignatureRequestMessage)

  val refundTxSignature = new TransactionSignature(BigInteger.ZERO, BigInteger.ZERO)
  val refundSignatureResponse = RefundSignatureResponse(sampleExchangeId, refundTxSignature)
  val refundSignatureResponseMessage = msg.RefundSignatureResponse.newBuilder()
    .setExchangeId(sampleExchangeId.value)
    .setTransactionSignature(ByteString.copyFrom(refundTxSignature.encodeToBitcoin()))
    .build()

  "Refund signature response" must behave like thereIsAMappingBetween(
    refundSignatureResponse, refundSignatureResponseMessage)
}
