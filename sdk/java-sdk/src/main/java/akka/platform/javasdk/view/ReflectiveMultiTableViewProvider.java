/*
 * Copyright (C) 2021-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.platform.javasdk.view;

import com.google.protobuf.Descriptors;
import akka.platform.javasdk.annotations.ComponentId;
import akka.platform.javasdk.impl.MessageCodec;
import akka.platform.javasdk.impl.reflection.Reflect;
import akka.platform.javasdk.impl.view.ViewMultiTableRouter;
import akka.platform.javasdk.impl.ComponentDescriptor;
import akka.platform.javasdk.impl.JsonMessageCodec;
import akka.platform.javasdk.impl.view.ReflectiveViewMultiTableRouter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public class ReflectiveMultiTableViewProvider<V> implements ViewProvider {

  private final Class<V> viewClass;
  private final BiFunction<Class<View<?>>, ViewContext, View<?>> factory;
  private final String viewId;
  private final ViewOptions options;
  private final JsonMessageCodec messageCodec;
  private final ComponentDescriptor componentDescriptor;

  public static <V> ReflectiveMultiTableViewProvider<V> of(
      Class<V> viewClass,
      JsonMessageCodec messageCodec,
      BiFunction<Class<View<?>>, ViewContext, View<?>> factory) {

    String viewId =
        Optional.ofNullable(viewClass.getAnnotation(ComponentId.class))
            .map(ComponentId::value)
            .orElseGet(viewClass::getName);

    return new ReflectiveMultiTableViewProvider<>(
        viewClass, factory, viewId, ViewOptions.defaults(), messageCodec);
  }

  private ReflectiveMultiTableViewProvider(
      Class<V> viewClass,
      BiFunction<Class<View<?>>, ViewContext, View<?>> factory,
      String viewId,
      ViewOptions options,
      JsonMessageCodec messageCodec) {
    this.viewClass = viewClass;
    this.factory = factory;
    this.viewId = viewId;
    this.options = options;
    this.messageCodec = messageCodec;
    this.componentDescriptor = ComponentDescriptor.descriptorFor(viewClass, messageCodec);
  }

  @Override
  public Descriptors.ServiceDescriptor serviceDescriptor() {
    return componentDescriptor.serviceDescriptor();
  }

  @Override
  public String viewId() {
    return viewId;
  }

  @Override
  public ViewOptions options() {
    return options;
  }

  @Override
  public ViewMultiTableRouter newRouter(ViewContext context) {
    Map<Class<View<?>>, View<?>> viewTables = new HashMap<>();
    for (Class<?> innerClass : viewClass.getDeclaredClasses()) {
      if (Reflect.isNestedViewTable(innerClass)) {
        @SuppressWarnings("unchecked")
        Class<View<?>> viewTableClass = (Class<View<?>>) innerClass;
        viewTables.put(viewTableClass, factory.apply(viewTableClass, context));
      }
    }
    return new ReflectiveViewMultiTableRouter(viewTables, componentDescriptor.commandHandlers());
  }

  @Override
  public Descriptors.FileDescriptor[] additionalDescriptors() {
    return new Descriptors.FileDescriptor[0];
  }

  @Override
  public Optional<MessageCodec> alternativeCodec() {
    return Optional.of(messageCodec);
  }
}
