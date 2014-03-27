package com.coinffeine.common.protocol.serialization

import com.google.protobuf.Descriptors.FieldDescriptor

import com.coinffeine.common.protocol.messages.PublicMessage
import com.coinffeine.common.protocol.messages.arbitration.CommitmentNotification
import com.coinffeine.common.protocol.messages.handshake._
import com.coinffeine.common.protocol.messages.brokerage._
import com.coinffeine.common.protocol.protobuf.{CoinffeineProtobuf => proto}
import com.coinffeine.common.protocol.protobuf.CoinffeineProtobuf.CoinffeineMessage._
import com.coinffeine.common.protocol.serialization.DefaultProtoMappings._

private[serialization] object DefaultProtocolSerialization extends ProtocolSerialization {

  override def toProtobuf(message: PublicMessage): proto.CoinffeineMessage = {
    val builder = proto.CoinffeineMessage.newBuilder
    message match {
      case m: ExchangeAborted => builder.setExchangeAborted(ProtoMapping.toProtobuf(m))
      case m: EnterExchange => builder.setEnterExchange(ProtoMapping.toProtobuf(m))
      case m: CommitmentNotification =>
        builder.setCommitmentNotification(ProtoMapping.toProtobuf(m))
      case m: OrderMatch => builder.setOrderMatch(ProtoMapping.toProtobuf(m))
      case m: Order => builder.setOrder(ProtoMapping.toProtobuf(m))
      case m: QuoteRequest => builder.setQuoteRequest(ProtoMapping.toProtobuf(m))
      case m: ExchangeRejection => builder.setExchangeRejection(ProtoMapping.toProtobuf(m))
      case m: RefundTxSignatureRequest =>
        builder.setRefundTxSignatureRequest(ProtoMapping.toProtobuf(m))
      case m: RefundTxSignatureResponse =>
        builder.setRefundTxSignatureResponse(ProtoMapping.toProtobuf(m))
      case _ => throw new IllegalArgumentException("Unsupported message: " + message)
    }
    builder.build()
  }

  override def fromProtobuf(message: proto.CoinffeineMessage): PublicMessage = {
    require(message.getAllFields.size() == 1)
    val descriptor: FieldDescriptor = message.getAllFields.keySet().iterator().next()
    descriptor.getNumber match {
      case EXCHANGEABORTED_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getExchangeAborted)
      case ENTEREXCHANGE_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getEnterExchange)
      case COMMITMENTNOTIFICATION_FIELD_NUMBER =>
        ProtoMapping.fromProtobuf(message.getCommitmentNotification)
      case ORDERMATCH_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getOrderMatch)
      case ORDER_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getOrder)
      case QUOTEREQUEST_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getQuoteRequest)
      case EXCHANGEREJECTION_FIELD_NUMBER => ProtoMapping.fromProtobuf(message.getExchangeRejection)
      case REFUNDTXSIGNATUREREQUEST_FIELD_NUMBER =>
        ProtoMapping.fromProtobuf(message.getRefundTxSignatureRequest)
      case REFUNDTXSIGNATURERESPONSE_FIELD_NUMBER =>
        ProtoMapping.fromProtobuf(message.getRefundTxSignatureResponse)
      case _ => throw new IllegalArgumentException("Unsupported message: " + descriptor.getFullName)
    }
  }
}
