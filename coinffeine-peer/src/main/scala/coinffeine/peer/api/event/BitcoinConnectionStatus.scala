package coinffeine.peer.api.event

import coinffeine.peer.api.event.BitcoinConnectionStatus.BlockchainStatus

/** An event reporting the state of the connection with the bitcoin network. */
case class BitcoinConnectionStatus(activePeers: Int, blockchainStatus: BlockchainStatus)
  extends CoinffeineAppEvent {

  def connected: Boolean = activePeers > 0
}

object BitcoinConnectionStatus {

  sealed trait BlockchainStatus

  /** The blockchain is fully downloaded */
  case object Downloaded extends BlockchainStatus

  /** Blockchain download is in progress */
  case class Downloading(totalBlocks: Int, remainingBlocks: Int) extends BlockchainStatus {
    require(totalBlocks > 0)

    def progress: Double = (totalBlocks - remainingBlocks) / totalBlocks.toDouble
  }
}
