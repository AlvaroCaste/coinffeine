package com.coinffeine.common

import com.coinffeine.common.test.UnitTest

class PeerConnectionTest extends UnitTest {

  "A peer connection" should "be parsed from a chain with hostname and port" in {
    PeerConnection.parse("coinffeine://example.com:9876") should
      be (PeerConnection("example.com", 9876))
  }

  it should "be parsed from a chain with only a hostname" in {
    PeerConnection.parse("coinffeine://example.com") should
      be (PeerConnection("example.com", PeerConnection.DefaultPort))
  }

  it should "be parsed from a chain with hostname and port and a trailing slash" in {
    PeerConnection.parse("coinffeine://example.com:9876/") should
      be (PeerConnection("example.com", 9876))
  }

  it should "be parsed from a chain with only a hostname and a trailing slash" in {
    PeerConnection.parse("coinffeine://example.com/") should
      be (PeerConnection("example.com", PeerConnection.DefaultPort))
  }

  it should "throw when missing scheme prefix" in {
    an [IllegalArgumentException] should be thrownBy PeerConnection.parse("example.com:9876")
  }
}
