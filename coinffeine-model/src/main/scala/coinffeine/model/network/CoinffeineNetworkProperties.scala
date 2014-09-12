package coinffeine.model.network

import coinffeine.model.properties.{MutableProperty, Property}

trait CoinffeineNetworkProperties {

  val activePeers: Property[Int]
  val brokerId: Property[Option[PeerId]]
}

class MutableCoinffeineNetworkProperties extends CoinffeineNetworkProperties {

  override val activePeers: MutableProperty[Int] = new MutableProperty(0)
  override val brokerId: MutableProperty[Option[PeerId]] = new MutableProperty(None)
}

object MutableCoinffeineNetworkProperties {

  trait Component {
    def coinffeineNetworkProperties: MutableCoinffeineNetworkProperties
  }
}
