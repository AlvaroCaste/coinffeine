package com.coinffeine.common.protocol.gateway

import akka.actor.Props

import com.coinffeine.common.PeerConnection
import com.coinffeine.common.exchange.PeerId
import com.coinffeine.common.protocol.messages.PublicMessage

object MessageGateway {

  /** Initialization message for the gateway. */
  case class Bind(id: PeerId, address: PeerConnection, brokerId: PeerId,
                  brokerConnection: PeerConnection)
  case class BoundTo(address: PeerConnection)
  case class BindingError(cause: Throwable)

  /** A message sent in order to forward another message to a given destination. */
  case class ForwardMessage[M <: PublicMessage](message: M, dest: PeerId)

  type Filter = ReceiveMessage[_ <: PublicMessage] => Boolean

  /** A message sent in order to subscribe for incoming messages.
    *
    * Each actor can only have one active subscription at a time. A second Subscribe message
    * sent to the gateway would overwrite any previous subscription.
    *
    * @param filter A filter function that indicates what messages are forwarded to the sender actor
    */
  case class Subscribe(filter: Filter)

  /** A message sent in order to unsubscribe from incoming message reception. */
  case object Unsubscribe

  /** A message send back to the subscriber. */
  case class ReceiveMessage[M <: PublicMessage](msg: M, sender: PeerId)

  /** An exception thrown when an error is found on message forward. */
  case class ForwardException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

  trait Component {
    def messageGatewayProps: Props
  }
}
