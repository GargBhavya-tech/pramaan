# Anonymised operational metrics

PS04 good-to-have: average liveness processing time, challenge completion time, liveness retry rate, liveness failure rate, as
anonymised operational metrics. `core/.../audit/LivenessMetrics.java`.

It is a consumer of the same `AuditEvent` stream the audit log receives, so the session code is unchanged:

```java
LivenessMetrics metrics = new LivenessMetrics(System::currentTimeMillis);
// wherever the audit events are accepted today, also pass them on:
audit = event -> { auditLog.accept(event); metrics.accept(event); };
System.out.println(metrics.report());
```

```text
workflow    attempts  retry%  fail%  avg-liveness-ms  avg-challenge-ms
RESIDENT          12    16.7   25.0             4210              1830
OPERATOR           5     0.0    0.0             3120                 -
```

(The table above is an illustration of the format, not a measurement.)

## Definitions

| Metric | Definition |
|---|---|
| processing time | first transition out of `IDLE` to the attempt ending, per attempt, in ms |
| challenge completion time | entering `CHALLENGING` to leaving it; only attempts that were challenged (otherwise `-`, never 0) |
| retry rate | attempts numbered above 1, as a share of all attempts |
| failure rate | attempts that did not end `ACCEPTED`, as a share of all attempts |

## What it deliberately is not

- **Anonymous by construction:** it keeps no resident, operator, centre or device id. The session id pairs a
  start with an end and is dropped when the attempt ends; the report carries only the workflow name, counts and averages.
- **Not an error rate of the detector.** A "failure" here is an attempt that did not end accepted. A high failure rate may mean
  attacks, a dark room, or a bad threshold; there is no ground truth in the event stream. APCER and BPCER need labelled data
  (`harness`), and none exists yet.
- **Time is passed in,** never read from a clock inside the class (`record(event, atMs)`), so tests simulate it.

Tests: `LivenessMetricsTest` (6 cases).
