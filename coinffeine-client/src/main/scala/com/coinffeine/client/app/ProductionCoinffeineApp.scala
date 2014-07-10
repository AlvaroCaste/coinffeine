package com.coinffeine.client.app

import com.coinffeine.client.peer.{CoinffeinePeerActor, MarketInfoActor}
import com.coinffeine.client.peer.orders.OrdersActor
import com.coinffeine.common.config.FileConfigComponent
import com.coinffeine.common.network.MainNetComponent
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.ProtoRpcMessageGateway
import com.coinffeine.common.protocol.serialization.DefaultProtocolSerializationComponent

object ProductionCoinffeineApp {

  trait Component
    extends DefaultCoinffeineApp.Component
    with CoinffeinePeerActor.Component
    with MarketInfoActor.Component
    with ProtocolConstants.DefaultComponent
    with OrdersActor.Component
    with ProtoRpcMessageGateway.Component
    with DefaultProtocolSerializationComponent
    with MainNetComponent
    with FileConfigComponent
}
