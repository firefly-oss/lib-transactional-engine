# Transactional Engine Deep‑Dive Tutorial — Orchestrating a Travel Booking Saga

This tutorial is a hands-on, step-by-step deep dive into the Firefly Transactional Engine using a scenario that is completely different from the README's example. We will orchestrate a Travel Booking Saga coordinating multiple services: Flights, Hotels, Car Rentals, Payments, and Notifications. You'll see how to model a DAG of steps, run them with concurrency, deal with failure and compensation, propagate headers over HTTP, and reason about idempotency, retries, and timeouts.

**🚀 This tutorial focuses on the modern API features:**
- **Typed StepInputs DSL** with lazy resolvers
- **SagaResult API** for comprehensive execution results
- **Parameter injection** using `@FromStep`, `@Header`, `@Headers`, `@Input` annotations
- **Duration-based configuration** for better readability
- **Programmatic saga building** with `SagaBuilder`

> **Note**: This tutorial uses the modern `execute()` API. The older `run()` API is deprecated but still functional. See the migration notes throughout for upgrading existing code.

If you're looking for the full reference, see README.md. Here we go deeper into the why and how with a realistic end-to-end flow.


## 0) Prerequisites
- Java 21+
- Spring Boot 3.x
- Reactor (Mono) — included with Spring WebFlux
- A build tool (Maven or Gradle)

Install the dependency:

Maven
```xml
<dependency>
  <groupId>com.catalis</groupId>
  <artifactId>lib-transactional-engine</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Gradle (Kotlin DSL)
```kotlin
dependencies {
  implementation("com.catalis:lib-transactional-engine:1.0.0-SNAPSHOT")
}
```


## 1) Enable the engine in your Spring Boot app
Add the `@EnableTransactionalEngine` annotation to a configuration class (commonly your main `@SpringBootApplication`).

```java
import com.catalis.transactionalengine.annotations.EnableTransactionalEngine;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableTransactionalEngine
@SpringBootApplication
public class App {
  public static void main(String[] args) {
    org.springframework.boot.SpringApplication.run(App.class, args);
  }
}
```

What this does:
- Registers SagaEngine, SagaRegistry, and default observability (`SagaEvents`).
- Scans for your `@Saga` classes and their `@SagaStep` methods at startup.


## 2) Problem definition — Travel booking, end to end
We need to orchestrate a customer’s trip:
- Reserve a flight
- Reserve a hotel
- Optionally reserve a car
- Finally capture payment and issue an itinerary

Key points for this scenario:
- Flight, Hotel, and Car reservations can be attempted concurrently after we create an internal booking record.
- Payment capture should only happen after all reservations confirm.
- If any reservation fails (or payment fails), we must undo previously completed reservations (cancel flight/hotel/car) and mark the booking as failed.
- We must propagate a correlation id and custom headers across all HTTP calls for traceability.

We will model this with a DAG, set per-step retries and timeouts, and specify compensations for each reversible step.


## 3) Define request/response models (inputs/outputs)
Create simple records/POJOs for the inputs to our steps. Keep them minimal for clarity.

```java
public record InitBookingCmd(String customerId, String tripId) {}
public record FlightReq(String from, String to, String date, int passengers) {}
public record HotelReq(String city, String checkIn, String checkOut, int rooms) {}
public record CarReq(String city, String pickupDate, String dropoffDate, String category) {}
public record PaymentReq(String customerId, long totalCents, String currency) {}

public record FlightRes(String reservationId) {}
public record HotelRes(String reservationId) {}
public record CarRes(String reservationId) {}
public record Itinerary(String bookingId, String pdfUrl) {}
```

Notes:
- Outputs are returned by step methods and automatically stored in `SagaContext` under the step id.
- Compensation methods may receive either the original input or the result (whichever is type-compatible), plus `SagaContext` if declared.


## 4) Visualize the DAG
We’ll create a root step `initBooking` that allocates a bookingId in our system. Once we have that, we can attempt the three reservations in parallel. After all succeed, we `capturePayment`. Finally, we `issueItinerary` and send a `notifyCustomer` message.

```mermaid
flowchart LR
  A[initBooking] --> B[reserveFlight]
  A --> C[reserveHotel]
  A --> D[reserveCar]
  B --> E[capturePayment]
  C --> E
  D --> E
  E --> F[issueItinerary]
  F --> G[notifyCustomer]
```

Compensation plan (reverse of what succeeded):
- cancelCar, cancelHotel, cancelFlight (best-effort) if payment fails or later steps fail
- markBookingFailed if we had created a booking
- revokeItinerary/notifyFailure for post-payment steps


## 5) Orchestrator implementation (@Saga + @SagaStep)
We’ll wire WebClient instances to call downstreams. We use `HttpCall.propagate` to ensure `X-Transactional-Id` and custom headers travel with every request.

```java
import com.catalis.transactionalengine.annotations.Saga;
import com.catalis.transactionalengine.annotations.SagaStep;
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.http.HttpCall;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Saga(name = "TravelBookingSaga")
@Service
public class TravelBookingOrchestrator {
  private final WebClient flights;
  private final WebClient hotels;
  private final WebClient cars;
  private final WebClient payments;
  private final WebClient docs;
  private final WebClient notify;

  public TravelBookingOrchestrator(WebClient.Builder builder) {
    this.flights  = builder.baseUrl("http://flights/api").build();
    this.hotels   = builder.baseUrl("http://hotels/api").build();
    this.cars     = builder.baseUrl("http://cars/api").build();
    this.payments = builder.baseUrl("http://payments/api").build();
    this.docs     = builder.baseUrl("http://docs/api").build();
    this.notify   = builder.baseUrl("http://notify/api").build();
  }

  // 1) Initialize booking (idempotent, fast) - using modern ISO-8601 duration
  @SagaStep(id = "initBooking", compensate = "markBookingFailed", timeout = "PT2S")
  public Mono<String> initBooking(InitBookingCmd cmd, SagaContext ctx) {
    return HttpCall.propagate(
        docs.post().uri("/bookings").bodyValue(Map.of(
            "customerId", cmd.customerId(),
            "tripId", cmd.tripId()
        )), ctx
    ).retrieve().bodyToMono(String.class) // returns bookingId
     .doOnNext(bid -> ctx.putHeader("X-Booking-Id", bid)); // propagate booking id downstream
  }

  public Mono<Void> markBookingFailed(String bookingId, SagaContext ctx) {
    return HttpCall.propagate(
        docs.post().uri("/bookings/{id}/fail", bookingId), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 2) Reserve flight (retriable with jitter)
  @SagaStep(id = "reserveFlight", compensate = "cancelFlight", dependsOn = {"initBooking"},
            retry = 2, backoff = "PT300MS", timeout = "PT5S", jitter = true)
  public Mono<FlightRes> reserveFlight(FlightReq req, SagaContext ctx) {
    return HttpCall.propagate(
        flights.post().uri("/reservations").bodyValue(req), ctx
    ).retrieve().bodyToMono(FlightRes.class);
  }

  public Mono<Void> cancelFlight(FlightRes res, SagaContext ctx) {
    return HttpCall.propagate(
        flights.post().uri("/reservations/{id}/cancel", res.reservationId()), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 3) Reserve hotel (parallel to flight with jitter)
  @SagaStep(id = "reserveHotel", compensate = "cancelHotel", dependsOn = {"initBooking"},
            retry = 2, backoff = "PT300MS", timeout = "PT5S", jitter = true, jitterFactor = 0.3)
  public Mono<HotelRes> reserveHotel(HotelReq req, SagaContext ctx) {
    return HttpCall.propagate(
        hotels.post().uri("/reservations").bodyValue(req), ctx
    ).retrieve().bodyToMono(HotelRes.class);
  }

  public Mono<Void> cancelHotel(HotelRes res, SagaContext ctx) {
    return HttpCall.propagate(
        hotels.post().uri("/reservations/{id}/cancel", res.reservationId()), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 4) Reserve car (optional, CPU-bound pricing calculation)
  @SagaStep(id = "reserveCar", compensate = "cancelCar", dependsOn = {"initBooking"},
            idempotencyKey = "car:standard", timeout = "PT4S", cpuBound = true)
  public Mono<CarRes> reserveCar(CarReq req, SagaContext ctx) {
    return HttpCall.propagate(
        cars.post().uri("/reservations").bodyValue(req), ctx
    ).retrieve().bodyToMono(CarRes.class);
  }

  public Mono<Void> cancelCar(CarRes res, SagaContext ctx) {
    return HttpCall.propagate(
        cars.post().uri("/reservations/{id}/cancel", res.reservationId()), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 5) Capture payment after ALL reservations are confirmed
  @SagaStep(id = "capturePayment", compensate = "refundPayment",
            dependsOn = {"reserveFlight", "reserveHotel", "reserveCar"}, timeout = "PT6S")
  public Mono<String> capturePayment(PaymentReq req, SagaContext ctx) {
    return HttpCall.propagate(
        payments.post().uri("/charges").bodyValue(req), ctx
    ).retrieve().bodyToMono(String.class); // chargeId
  }

  public Mono<Void> refundPayment(String chargeId, SagaContext ctx) {
    return HttpCall.propagate(
        payments.post().uri("/charges/{id}/refund", chargeId), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 6) Issue itinerary using parameter injection (modern approach)
  @SagaStep(id = "issueItinerary", compensate = "revokeItinerary", dependsOn = {"capturePayment"}, timeout = "PT3S")
  public Mono<Itinerary> issueItinerary(
      @FromStep("initBooking") String bookingId,
      @FromStep("capturePayment") String chargeId,
      @Header("X-User-Id") String userId,
      SagaContext ctx) {
    return HttpCall.propagate(
        docs.post().uri("/itineraries").bodyValue(Map.of(
            "bookingId", bookingId,
            "chargeId", chargeId,
            "userId", userId
        )), ctx
    ).retrieve().bodyToMono(Itinerary.class);
  }

  // Alternative: Traditional approach (still supported)
  // public Mono<Itinerary> issueItinerary(SagaContext ctx) {
  //   String bookingId = (String) ctx.getResult("initBooking");
  //   String chargeId = (String) ctx.getResult("capturePayment");
  //   String userId = ctx.headers().get("X-User-Id");
  //   return HttpCall.propagate(
  //       docs.post().uri("/itineraries").bodyValue(Map.of(
  //           "bookingId", bookingId,
  //           "chargeId", chargeId,
  //           "userId", userId
  //       )), ctx
  //   ).retrieve().bodyToMono(Itinerary.class);
  // }

  public Mono<Void> revokeItinerary(Itinerary it, SagaContext ctx) {
    return HttpCall.propagate(
        docs.post().uri("/itineraries/revoke").bodyValue(Map.of("bookingId", it.bookingId())), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  // 7) Notify customer; if it fails we log but do not attempt to undo previous business effects
  @SagaStep(id = "notifyCustomer", compensate = "notifyFailure", dependsOn = {"issueItinerary"})
  public Mono<Void> notifyCustomer(SagaContext ctx) {
    String bookingId = (String) ctx.getResult("initBooking");
    return HttpCall.propagate(
        notify.post().uri("/email").bodyValue(Map.of(
            "template", "itinerary",
            "bookingId", bookingId
        )), ctx
    ).retrieve().bodyToMono(Void.class);
  }

  public Mono<Void> notifyFailure(SagaContext ctx) {
    String bookingId = (String) ctx.getResult("initBooking");
    return HttpCall.propagate(
        notify.post().uri("/email").bodyValue(Map.of(
            "template", "failure",
            "bookingId", bookingId
        )), ctx
    ).retrieve().bodyToMono(Void.class);
  }
}
```

Why compensations like notifyFailure exist: the engine requires a compensation method per step. For steps that are logically non-reversible (e.g., sending an email), use a compensating action that records/alerts the failure (a “noop” is fine if you prefer). For read-only steps, you can also use a noop compensation.


## 6) Executing the saga
Use `SagaEngine` to execute by name with typed `StepInputs` and receive a typed `SagaResult`. Supply inputs for each step that expects an input. Steps that only need `SagaContext` can omit inputs.

```java
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.core.SagaResult;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepInputs;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Service
public class TravelService {
  private final SagaEngine engine;

  public TravelService(SagaEngine engine) { this.engine = engine; }

  public Mono<BookingResult> bookTrip(String customerId, String tripId) {
    SagaContext ctx = new SagaContext();
    ctx.putHeader("X-User-Id", customerId);
    ctx.putHeader("X-Tenant", "eu-west");
    ctx.putHeader("X-Request-Source", "web-ui");

    StepInputs inputs = StepInputs.builder()
        .forStepId("initBooking", new InitBookingCmd(customerId, tripId))
        .forStepId("reserveFlight", new FlightReq("MAD", "SFO", "2025-10-01", 2))
        .forStepId("reserveHotel", new HotelReq("San Francisco", "2025-10-01", "2025-10-07", 1))
        .forStepId("reserveCar", new CarReq("San Francisco", "2025-10-01", "2025-10-07", "standard"))
        .forStepId("capturePayment", new PaymentReq(customerId, 2_450_00, "USD"))
        .build();

    return engine
        .execute("TravelBookingSaga", inputs, ctx)
        .map(this::processResult);
  }

  private BookingResult processResult(SagaResult result) {
    if (result.isSuccess()) {
      // Extract typed results from successful execution
      String bookingId = result.resultOf("initBooking", String.class).orElse("unknown");
      Itinerary itinerary = result.resultOf("issueItinerary", Itinerary.class).orElse(null);
      Duration totalDuration = result.duration();
      
      return new BookingResult(
          true, 
          bookingId, 
          itinerary, 
          null, 
          totalDuration,
          result.steps().size()
      );
    } else {
      // Handle failure with detailed error information
      String failedStep = result.firstErrorStepId().orElse("unknown");
      List<String> compensatedSteps = result.compensatedSteps();
      Throwable error = result.error().orElse(null);
      
      return new BookingResult(
          false, 
          null, 
          null, 
          new BookingError(failedStep, error, compensatedSteps),
          result.duration(),
          result.steps().size()
      );
    }
  }

  public record BookingResult(
      boolean success,
      String bookingId,
      Itinerary itinerary,
      BookingError error,
      Duration executionTime,
      int totalSteps
  ) {}

  public record BookingError(
      String failedStep,
      Throwable cause,
      List<String> compensatedSteps
  ) {}
}
```

What runs when:
- `initBooking` runs first. Its result (bookingId) is stored in context.
- `reserveFlight`, `reserveHotel`, and `reserveCar` run concurrently (same DAG layer) after `initBooking`.
- `capturePayment` waits for all three reservations to complete.
- `issueItinerary` waits for payment; then `notifyCustomer` runs.


## 7) Failure walkthrough (deep dive)
Consider a failure in `reserveHotel` after `reserveFlight` succeeded, `reserveCar` succeeded, and before `capturePayment` runs.
- The engine stops scheduling further steps and begins compensation for completed steps.
- Compensation order is the reverse completion order (not reverse declaration order). If `reserveCar` finished last, it will be compensated first: `cancelCar`, then `cancelFlight`, and finally `markBookingFailed` for `initBooking` if nothing else succeeded.
- `SagaContext` statuses you should expect:
  - `reserveHotel = FAILED`
  - `reserveFlight = COMPENSATED`
  - `reserveCar = COMPENSATED`
  - `initBooking = COMPENSATED` (after `markBookingFailed`)
- The saga completes with success=false, and observability hooks (`SagaEvents`) are fired accordingly.

If failure happens after payment capture, the compensation will include `refundPayment` and then reservation cancellations as needed, followed by `markBookingFailed`. If `issueItinerary` fails, we’ll call `revokeItinerary` and optionally trigger `notifyFailure`.


## 8) Retries, backoff, and timeouts — choosing values
- Network steps (`reserveFlight`, `reserveHotel`) often benefit from small retries (1–3) with a short fixed backoff (100–500 ms). Avoid large retry counts that prolong user wait times.
- Use `timeoutMs` per step to bound user-perceived latency and avoid hanging requests; keep them aligned with downstream SLAs.
- Beware of double retries: if your HTTP client retries and the engine also retries, you may multiply attempts unintentionally.

Example configuration we used:
```java
@SagaStep(id = "reserveHotel", compensate = "cancelHotel", dependsOn = {"initBooking"},
          retry = 2, backoffMs = 300, timeoutMs = 5000)
```


## 9) Idempotency (per run) and when to use it
- `idempotencyKey` skips a step if the same key was used earlier in the SAME saga run.
- This is useful for optional or re-entrant steps that may get scheduled again within the run due to retries/timeouts or graph structure.
- Cross-run idempotency (between different saga runs or days) is not handled by the engine; design your downstream APIs to be idempotent using natural keys.

Example:
```java
@SagaStep(id = "reserveCar", compensate = "cancelCar", dependsOn = {"initBooking"},
          idempotencyKey = "car:standard")
```


## 10) Argument resolution for compensations (nuance)
When a compensation method expects a business argument, the engine resolves it in this order:
1) The original step input if assignable to the parameter type
2) Else, the step result if assignable
3) Else, null
Additionally, `SagaContext` is injected if declared. This is why we can write `cancelHotel(HotelRes res, SagaContext ctx)` — the engine passes the step result.


## 11) Observability — what to look for
Out-of-the-box, the engine emits lifecycle events via `SagaEvents`. The default `SagaLoggerEvents` prints structured key=value logs like:
```
saga_event=start saga=TravelBookingSaga sagaId=...
saga_event=step_success saga=TravelBookingSaga stepId=reserveFlight attempts=1 latencyMs=123
saga_event=step_failed saga=TravelBookingSaga stepId=reserveHotel attempts=3 latencyMs=5000 error=...
saga_event=completed saga=TravelBookingSaga success=false
```
In addition to onStart(saga,id) and step success/failure events, the engine also publishes:
- onStart(saga,id, ctx): with access to SagaContext for header propagation and tracing; the default tracing sink injects X-Trace-Id.
- onStepStarted(saga,id,step): when a step transitions to RUNNING (useful for queue depth/concurrency metrics).
- onStepSkippedIdempotent(saga,id,step): when a step is skipped due to per-run idempotency.

Auto-configuration and composition:
- If you don’t declare your own `SagaEvents` bean, a composite is created including the logger sink and, if available, `SagaMicrometerEvents` and `SagaTracingEvents` (wired when a `MeterRegistry` or `Tracer` bean is present).
- Micrometer metrics include counters/timers like `saga.step.started`, `saga.step.completed{outcome=*}`, `saga.step.latency`, `saga.step.attempts`, and `saga.run.completed`.
- Tracing creates a span for the saga and one per step, and injects `X-Trace-Id` into `SagaContext.headers()` for HTTP propagation.

You can provide a custom `SagaEvents` bean to integrate with metrics/tracing. For low-level timings of raw method invocations, enable DEBUG for `StepLoggingAspect`.


## 12) Testing the Travel Booking Saga
Use Reactor StepVerifier to assert success/failure and inspect results in `SagaContext`.

```java
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.engine.SagaEngine;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

class TravelBookingTest {
  @Test
  void successfulBooking_emitsItinerary() {
    SagaEngine engine = /* injected */ null;
    SagaContext ctx = new SagaContext("corr-travel-1");

    StepInputs inputs = StepInputs.builder()
        .forStepId("initBooking", new InitBookingCmd("cust-100", "trip-42"))
        .forStepId("reserveFlight", new FlightReq("MAD", "SFO", "2025-10-01", 2))
        .forStepId("reserveHotel", new HotelReq("San Francisco", "2025-10-01", "2025-10-07", 1))
        .forStepId("reserveCar", new CarReq("San Francisco", "2025-10-01", "2025-10-07", "standard"))
        .forStepId("capturePayment", new PaymentReq("cust-100", 2_450_00, "USD"))
        .build();

    StepVerifier.create(engine.execute("TravelBookingSaga", inputs, ctx))
    .expectNextMatches(res -> res.resultOf("issueItinerary", Itinerary.class).isPresent())
    .verifyComplete();
  }
}
```

Tips:
- In tests, you can mock downstream services or stub WebClient calls.
- Inspect `ctx.getStatus(stepId)` to assert `DONE`, `FAILED`, or `COMPENSATED`.


## 13) Programmatic (no-annotations) variant — modern approach
You can build the same saga dynamically with the fluent builder and functional handlers. This is useful for module-scoped flows or heavy testing. The modern API uses Duration-based configuration and typed StepInputs.

```java
import com.catalis.transactionalengine.core.SagaContext;
import com.catalis.transactionalengine.engine.SagaEngine;
import com.catalis.transactionalengine.engine.StepHandler;
import com.catalis.transactionalengine.engine.StepInputs;
import com.catalis.transactionalengine.registry.SagaBuilder;
import com.catalis.transactionalengine.registry.SagaDefinition;
import reactor.core.publisher.Mono;
import java.time.Duration;

// Modern approach with Duration-based configuration
SagaDefinition travel = SagaBuilder.saga("travel")
  .step("initBooking")
    .timeout(Duration.ofSeconds(2))
    .handler((StepHandler<InitBookingCmd, String>) (in, ctx) -> Mono.just("B-123"))
    .add()
  .step("reserveFlight")
    .dependsOn("initBooking")
    .retry(2)
    .backoff(Duration.ofMillis(300))
    .timeout(Duration.ofSeconds(5))
    .handler((StepHandler<FlightReq, FlightRes>) (in, ctx) -> Mono.just(new FlightRes("F-1")))
    .add()
  .step("reserveHotel")
    .dependsOn("initBooking")
    .retry(2)
    .backoff(Duration.ofMillis(300))
    .timeout(Duration.ofSeconds(5))
    .handler((StepHandler<HotelReq, HotelRes>) (in, ctx) -> Mono.just(new HotelRes("H-1")))
    .add()
  .step("reserveCar")
    .dependsOn("initBooking")
    .idempotencyKey("car:standard")
    .timeout(Duration.ofSeconds(4))
    .handler((StepHandler<CarReq, CarRes>) (in, ctx) -> Mono.just(new CarRes("C-1")))
    .add()
  .step("capturePayment")
    .dependsOn("reserveFlight", "reserveHotel", "reserveCar")
    .timeout(Duration.ofSeconds(6))
    .handler((StepHandler<PaymentReq, String>) (in, ctx) -> Mono.just("charge-OK"))
    .add()
  .step("issueItinerary")
    .dependsOn("capturePayment")
    .timeout(Duration.ofSeconds(3))
    .handler((StepHandler<Void, Itinerary>) (in, ctx) -> {
      // Access previous step results from context
      String bookingId = (String) ctx.getResult("initBooking");
      String chargeId = (String) ctx.getResult("capturePayment");
      return Mono.just(new Itinerary(bookingId, "http://docs/itinerary.pdf"));
    })
    .add()
  .build();

// Execute with modern StepInputs DSL
SagaContext ctx = new SagaContext();
ctx.putHeader("X-User-Id", "u-123");

StepInputs inputs = StepInputs.builder()
  .forStepId("initBooking", new InitBookingCmd("cust-100", "trip-42"))
  .forStepId("reserveFlight", new FlightReq("MAD", "SFO", "2025-10-01", 2))
  .forStepId("reserveHotel", new HotelReq("San Francisco", "2025-10-01", "2025-10-07", 1))
  .forStepId("reserveCar", new CarReq("San Francisco", "2025-10-01", "2025-10-07", "standard"))
  .forStepId("capturePayment", new PaymentReq("cust-100", 2_450_00, "USD"))
  .build();

// Execute and get typed result
Mono<SagaResult> result = sagaEngine.execute(travel, inputs, ctx);
SagaResult sagaResult = result.block();

if (sagaResult.isSuccess()) {
  Itinerary itinerary = sagaResult.resultOf("issueItinerary", Itinerary.class).orElse(null);
  // Process successful booking
} else {
  String failedStep = sagaResult.firstErrorStepId().orElse("unknown");
  // Handle failure
}
```

**🔄 Migration from deprecated API:**
```java
// Old approach (deprecated but still works)
// Map<String, Object> inputs = Map.of(...);
// Mono<Map<String, Object>> result = sagaEngine.run(travel, inputs, ctx);

// New approach (recommended)
// StepInputs inputs = StepInputs.builder()...build();
// Mono<SagaResult> result = sagaEngine.execute(travel, inputs, ctx);
```

**Advanced: StepInputs with lazy resolvers in programmatic sagas:**
```java
StepInputs dynamicInputs = StepInputs.builder()
  .forStepId("initBooking", new InitBookingCmd("cust-100", "trip-42"))
  .forStepId("reserveFlight", new FlightReq("MAD", "SFO", "2025-10-01", 2))
  // Lazy resolver: issueItinerary input depends on previous results
  .forStepId("issueItinerary", ctx -> {
    String bookingId = (String) ctx.getResult("initBooking");
    String userId = ctx.headers().get("X-User-Id");
    return new IssueItineraryReq(bookingId, userId);
  })
  .build();
```


## 14) Practical guidance and edge cases
- Compensation must be best-effort and idempotent. Design downstream cancel/refund APIs so calling them twice is harmless.
- Concurrency: Steps in the same layer run concurrently. Ensure downstreams tolerate concurrent requests for the same logical customer/trip when applicable.
- Long chains: If your graph has many layers, consider grouping some calls behind a service to reduce orchestration complexity.
- Timeouts vs. retries: prefer smaller timeouts with a couple of retries over very large timeouts.
- Passing data: Use results in `SagaContext` to derive inputs for later steps (we used bookingId from `initBooking`). You can also place additional metadata into context headers for cross-cutting concerns.


## 15) Run tests in this repo
- All tests: `mvn clean test`
- One class: `mvn -Dtest=com.catalis.transactionalengine.engine.SagaEngineTest test`
- One method: `mvn -Dtest=SagaEngineTest#timeoutFailsStep test`


## 16) Summary
We built a Travel Booking Saga that:
- Models a DAG with concurrent reservations
- Uses per-step retries, backoff, and timeouts
- Propagates correlation id and custom headers over HTTP
- Compensates in reverse completion order on failure
- Demonstrates per-run idempotency and nuanced compensation argument resolution

This example is intentionally different from the README’s payment/order flow and goes deeper into the orchestration and failure semantics you’ll apply in production.

## 17) Parameter injection (multi-parameter)
The engine can inject values into step method parameters by reading annotations from the original method signature. You can mix and match:
- @FromStep("id"): inject the result of another step
- @Header("name"): inject a single outbound header
- @Headers: inject all headers as Map<String,String>
- @Input or @Input("key"): inject the step input (or a key from a Map input)
- SagaContext: injected by type

Example in this travel saga:
```java
@SagaStep(id = "prepareDocs", compensate = "noop", dependsOn = {"reserveFlight", "reserveHotel"})
public Mono<Void> prepareDocs(
  @FromStep("reserveFlight") FlightRes flight,
  @FromStep("reserveHotel") HotelRes hotel,
  @Header("X-User-Id") String user,
  SagaContext ctx
) {
  // Build a consolidated document request using flight + hotel + user
  return Mono.empty();
}
```
Validation rules are applied at startup so misconfigurations fail fast (unknown step ids, wrong parameter types for @Headers, etc.).

## 18) StepInputs DSL with lazy resolvers
Prefer StepInputs.builder() over raw Map inputs. You can provide concrete values or lazy resolvers that will be evaluated right before the step runs and then cached for compensation.

Concrete values by step id:
```java
StepInputs inputs = StepInputs.builder()
  .forStepId("initBooking", new InitBookingCmd("cust-100", "trip-42"))
  .forStepId("reserveFlight", new FlightReq("MAD", "SFO", "2025-10-01", 2))
  .build();
```

Lazy resolvers that depend on context and prior results:
```java
StepInputs inputs = StepInputs.builder()
  .forStepId("issueItinerary", c -> new IssueReq(
      (String) c.getResult("initBooking"),
      c.headers().get("X-User-Id")
  ))
  .build();
```
These values are cached once resolved so the same input is reused by compensation if needed.

Happy trips and reliable compensations!


## Cancellation

Reactor-based execution implies cooperative cancellation. When a caller cancels the subscription returned by SagaEngine:

- In-flight steps in the current layer continue to completion; running user code is not forcibly interrupted.
- No further layers are started after cancellation is observed.
- Cancellation alone does not trigger compensation. Compensation occurs only when a step fails and the engine transitions into the failure path.

Recommended practices:
- Implement steps using non-blocking, cancellable clients and avoid blocking calls.
- Use doOnCancel in step Monos to propagate cancel signals to downstream clients where supported.
- If you need compensations on user aborts, introduce a guard step that can fail fast on a cancellation/header flag, causing the engine to compensate prior steps.

Notes:
- SagaBuilder provides Duration-based configuration: backoff(Duration) and timeout(Duration). Millisecond variants are deprecated.
- SagaEvents.onCompensated is emitted for both success and error cases; a null error indicates a successful compensation.
