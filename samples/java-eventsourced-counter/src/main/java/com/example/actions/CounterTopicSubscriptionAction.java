package com.example.actions;

import akka.platform.javasdk.annotations.ComponentId;
import com.example.CounterEvent.ValueIncreased;
import com.example.CounterEvent.ValueMultiplied;
import akka.platform.javasdk.action.Action;
import akka.platform.javasdk.annotations.Consume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ComponentId("counter-topic-subscription")
@Consume.FromTopic(value = "counter-events") // <1>
public class CounterTopicSubscriptionAction extends Action {

  private Logger logger = LoggerFactory.getLogger(CounterTopicSubscriptionAction.class);

  public Action.Effect<Confirmed> onValueIncreased(ValueIncreased event) { // <2>
    logger.info("Received increased event: " + event.toString());
    return effects().reply(Confirmed.instance); // <3>
  }

  public Action.Effect<Confirmed> onValueMultiplied(ValueMultiplied event) { // <5>
    logger.info("Received multiplied event: " + event.toString());
    return effects().reply(Confirmed.instance); // <6>
  }
}
// end::class[]