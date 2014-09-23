name := "coinffeine-peer"

ScoverageKeys.excludedPackages in ScoverageCompile := "scalaxb;soapenvelope11;.*generated.*"

libraryDependencies ++= Dependencies.akka ++ Seq(
  Dependencies.h2 % "test",
  Dependencies.jcommander,
  Dependencies.netty,
  Dependencies.scalacheck % "test",
  // Support libraries for scalaxb
  Dependencies.dispatch,
  Dependencies.scalaParser,
  Dependencies.scalaXml,
  Dependencies.htmlunit
)
