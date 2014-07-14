package com.coinffeine.common.akka

import akka.actor.Props

import com.coinffeine.common.akka.ConstantValueActor.{SetValue, UnsetValue}
import com.coinffeine.common.test.AkkaSpec

class ConstantValueActorTest extends AkkaSpec("ConstantValueActorTest") {
  val instance = system.actorOf(Props[ConstantValueActor])

  "A constant value actor" should "start ignoring all non-control messages" in {
    instance ! 5
    expectNoMsg()
  }

  it should "reply to incoming messages after a value has been set" in {
    instance ! SetValue(9)
    instance ! "Hello"
    expectMsg(9)
  }

  it should "stop replying to incoming messages after the value is unset" in {
    instance ! UnsetValue
    instance ! "Hello"
    expectNoMsg()
  }
}
