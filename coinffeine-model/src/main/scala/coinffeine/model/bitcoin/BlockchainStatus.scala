package coinffeine.model.bitcoin

sealed trait BlockchainStatus

object BlockchainStatus {

  /** The blockchain is fully downloaded */
  case object NotDownloading extends BlockchainStatus

  /** Blockchain download is in progress */
  case class Downloading(totalBlocks: Int, remainingBlocks: Int) extends BlockchainStatus {
    require(totalBlocks > 0)

    def progress: Double = (totalBlocks - remainingBlocks) / totalBlocks.toDouble
  }
}
