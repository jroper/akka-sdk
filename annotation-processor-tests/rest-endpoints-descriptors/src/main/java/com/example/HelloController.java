/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package com.example;

import kalix.javasdk.annotations.http.Endpoint;
import kalix.javasdk.annotations.http.Get;

@Endpoint("/hello")
public class HelloController {

  @Get
  public String hello() {
    return "Hello, World!";
  }
}
