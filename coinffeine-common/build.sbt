name := "Coinffeine Common"

// TODO: evaluate scalaxb as a Scalaish replacement of Axis2
libraryDependencies ++= Dependencies.axis2 ++ Dependencies.akka ++ Seq(
  Dependencies.bitcoinj,
  Dependencies.netty,
  Dependencies.protobufRpc
)
