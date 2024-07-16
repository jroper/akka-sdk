package store.view.structured;

import akka.platform.javasdk.annotations.Query;
import akka.platform.javasdk.annotations.Consume;
import akka.platform.javasdk.annotations.Table;
import akka.platform.javasdk.annotations.ComponentId;
import akka.platform.javasdk.view.View;
import store.customer.api.CustomerEntity;
import store.customer.domain.CustomerEvent;
import store.order.api.OrderEntity;
import store.order.domain.Order;
import store.product.api.ProductEntity;
import store.product.domain.ProductEvent;
import store.view.QueryParameters;
import store.view.model.Customer;
import store.view.model.Product;

@ComponentId("structured-customer-orders")
public class StructuredCustomerOrdersView {

  // tag::query[]
  @Query( // <1>
    """
      SELECT
       customers.customerId AS id,
       (name,
        address.street AS address1,
        address.city AS address2,
        email AS contactEmail) AS shipping,
       (products.productId AS id,
        productName AS name,
        quantity,
        (price.currency, price.units, price.cents) AS value,
        orderId,
        createdTimestamp AS orderCreatedTimestamp) AS orders
      FROM customers
      JOIN orders ON orders.customerId = customers.customerId
      JOIN products ON products.productId = orders.productId
      WHERE customers.customerId = :customerId
      ORDER BY orders.createdTimestamp
      """)
  public CustomerOrders get(QueryParameters params) {
    return null;
  }
  // end::query[]

  @Table("customers")
  @Consume.FromEventSourcedEntity(CustomerEntity.class)
  public static class Customers extends View<Customer> {
    public Effect<Customer> onEvent(CustomerEvent event) {
      return switch (event) {
        case CustomerEvent.CustomerCreated created -> {
          String id = updateContext().eventSubject().orElse("");
          yield effects()
            .updateState(new Customer(id, created.email(), created.name(), created.address()));
        }

        case CustomerEvent.CustomerNameChanged nameChanged ->
          effects().updateState(viewState().withName(nameChanged.newName()));

        case CustomerEvent.CustomerAddressChanged addressChanged ->
          effects().updateState(viewState().withAddress(addressChanged.newAddress()));
      };
    }
  }

  @Table("products")
  @Consume.FromEventSourcedEntity(ProductEntity.class)
  public static class Products extends View<Product> {
    public Effect<Product> onEvent(ProductEvent event) {
      return switch (event) {
        case ProductEvent.ProductCreated created -> {
          String id = updateContext().eventSubject().orElse("");
          yield effects().updateState(new Product(id, created.name(), created.price()));
        }

        case ProductEvent.ProductNameChanged nameChanged ->
          effects().updateState(viewState().withProductName(nameChanged.newName()));

        case ProductEvent.ProductPriceChanged priceChanged ->
          effects().updateState(viewState().withPrice(priceChanged.newPrice()));
      };
    }
  }

  @Table("orders")
  @Consume.FromKeyValueEntity(OrderEntity.class)
  public static class Orders extends View<Order> {
  }
}
