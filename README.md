# Submission by Sam Dowell

# Starling Seven-Day Round-Up

A Java 25 and Spring Boot service that calculates round-ups for a selected seven-day week and transfers the total into a Starling savings goal.

## Prerequisites

- Java 25
- A Starling sandbox customer
- A sandbox access token permitted to read accounts, transaction feeds, and savings goals, and to create and fund savings goals

Gradle does not need to be installed. Use the committed Gradle 9.7 Wrapper from the project root.

## Configuration

Set the required environment variable before starting the service:

```shell
export STARLING_ACCESS_TOKEN='your-sandbox-token'
```

`STARLING_BASE_URL` is optional and defaults to `https://api-sandbox.starlingbank.com`.
`SERVER_PORT` is optional and defaults to `8080`.
`STARLING_USER_AGENT` is optional and overrides the default `round-up/1.0`, for example:

```shell
export STARLING_USER_AGENT='round-up-challenge/1.0 your-email@example.com'
```

Do not put tokens in source-controlled property files, environment files, or command-line arguments.

## Build And Run

From the project root:

```shell
./gradlew clean build
./gradlew bootRun
```

Tests generate JaCoCo XML and HTML coverage reports. Open
`build/reports/jacoco/test/html/index.html` for the HTML report. The `build` task
runs `check`, which enforces a minimum 80% line coverage ratio.

The service listens on `http://localhost:8080` by default.

Interactive API documentation is available at `http://localhost:8080/swagger-ui.html`.

## API

Create and transfer the round-up for a seven-day window beginning on the supplied UTC date. Generate the transfer UUID in the calling system and retain it until the request outcome is known:

```shell
curl --fail-with-body \
  --request POST \
  --header 'Content-Type: application/json' \
  --data '{"startDate":"2026-08-24","transferUid":"cccccccc-cccc-4ccc-cccc-cccccccccccc"}' \
  http://localhost:8080/api/v1/round-ups
```

A successful transfer returns `200 OK`:

```json
{
  "periodStart": "2026-08-24T00:00:00Z",
  "periodEnd": "2026-08-30T23:59:59.999999999Z",
  "eligibleTransactionCount": 3,
  "total": {
    "currency": "GBP",
    "minorUnits": 158
  },
  "transferUid": "cccccccc-cccc-4ccc-cccc-cccccccccccc"
}
```

A period whose total is zero also returns `200 OK`. The `transferUid` field is `null` because the Savings Goals APIs are not called.

If the outcome is unknown, retry with the same `startDate` and `transferUid`. Use a new UUID for a different week or genuinely new round-up request. Reusing a UUID for unrelated work is invalid caller behavior.

## Error Contract

Errors have a stable shape:

```json
{
  "code": "SELECTION_CONFLICT",
  "message": "Expected exactly one GBP primary account but found 2"
}
```

Status codes:

- `400` for malformed input or a missing/invalid start date or transfer UUID
- `409` for account-selection conflicts, missing identifiers on a selected account or savings goal, or a transfer UUID already used for a different transfer (`TRANSFER_IDEMPOTENCY_MISMATCH` — reuse the same UUID only to retry the same round-up, and use a new UUID for new work)
- `422` when Starling rejects a valid operation
- `502` for an invalid upstream response, including Starling authentication or authorization failures (an expired token or missing scope, reported as `STARLING_AUTH_FAILED`)
- `503` for temporary Starling unavailability
- `500` for an unexpected internal failure

Upstream errors are sanitized. Tokens and authorization headers are not included in responses or application-owned log messages.

## Round-Up Rules

### Specification Ambiguities And Assumptions

The supplied specification states:

> For a customer, take all the transactions in a given week and round them up to the nearest
> pound. For example with spending of £4.35, £5.20 and £0.87, the round-up would be £1.58.
> This amount should then be transferred into a savings goal, helping the customer save for
> future adventures.

I have made the following assumptions based on specific phrases in the spec:

- **"For a customer"**: a Starling access token identifies one account holder and the accounts that holder has authorised, not necessarily every account belonging to the real-world customer. Accounts belonging to another account holder require separate authorisation and are outside this implementation's scope.
- **"All the transactions"**:
  - The implementation assumes that an account holder has exactly one GBP account with `accountType == PRIMARY`; this field is Starling's internal account category, not the account-holder type. Zero or multiple matches are treated as a conflict rather than selecting an account by response order.
  - The service reads the selected account's main feed using its `defaultCategoryUid`; it does not aggregate transactions from spending spaces or other accounts.
  - Only outbound transactions are treated as spending. Incoming transactions and internal transfers are excluded. Transaction status is not used as an eligibility condition.
  - Transactions created by this round-up application are ignored so they cannot themselves be rounded up. They are identified by the application-owned `round-up:` reference prefix.
- **"In a given week"**: the caller supplies an ISO `startDate` in `YYYY-MM-DD` format. It may be any day of the week and defines seven consecutive UTC calendar days, starting at midnight and ending one nanosecond before midnight on `startDate + 7 days`.
- **"Round them up to the nearest pound"**:
  - Because the specification expresses both the transactions and round-up in pounds, only accounts with `currency == GBP` are considered.
  - Each transaction contributes the difference to the next whole pound; a whole-pound amount contributes zero.
- **"Transferred into a savings goal"**: the specification does not identify which savings goal to use. The service reuses the first active goal named exactly `Weekly Round-ups`, or creates a GBP goal with that name when none exists. Non-active goals (for example archived or restricted) are skipped.

An eligible feed item must:

- use account-denominated currency `GBP`
- have direction `OUT`
- not have source `INTERNAL_TRANSFER`
- not have a reference beginning with the application-owned `round-up:` prefix
- have `transactionTime` inside the requested seven-day week

Calculations use the account-denominated amount rather than `sourceAmount`, which may represent foreign currency, and use integer minor units throughout.

Transfers created by this service use the reference `round-up:<weekStart>` (for example `round-up:2026-08-24`). The reserved `round-up:` prefix lets later requests explicitly exclude prior round-up transfers, preventing round-ups from being rounded up again even if Starling's source classification changes; exclusion keys off the prefix alone, so the week-start suffix is purely descriptive and avoids exposing the caller's transfer UUID.

## Packages

- `com.roundup.RoundUpApplication` is the root-package entry point.
- `configuration` owns typed Starling properties and the authenticated `RestClient`.
- `controller` owns the HTTP endpoint and exception mapping.
- `service` owns transaction-window creation, filtering, arithmetic, account and goal selection, orchestration, Starling HTTP calls, retries, and sanitized failure logging.
- `exception` contains the application's typed conflict exceptions mapped to HTTP responses.
- `model` contains the internal domain records: money, calculation, and transaction-period.
- `model.api` contains the HTTP contract records: request, result, and error.
- `model.starling` contains Starling wire records, including the shared `CurrencyAndAmount` money type.

## Retry And Concurrency Limitations

Reads retry bounded transient transport failures, HTTP `429`, and HTTP `5xx`. Transfers retry the same transient failures with the supplied transferId to ensure idempotency. Savings-goal creation is not retried because it has no caller-owned idempotency key.

Retry pauses use linear backoff: the configured base delay is multiplied by the failed attempt number before the next attempt.

Transfer idempotency relies on the caller reusing the same start date and UUID for retries and on Starling honoring that identifier. This service has no durable idempotency store and does not replay an earlier response.

Callers must therefore use the same UUID only while resolving one logical request and must use a new UUID for new work. The caller-provided UUID is passed to Starling unchanged.

Duplicate goal handling is best-effort. Concurrent first requests may both observe a missing fixed-name goal and create duplicates. Later requests reuse the first exact-name match returned by Starling, whose ordering is not guaranteed, and the service does not attempt to detect or reconcile duplicate goals. Starling requires a caller-generated transfer UUID but does not explicitly document duplicate handling for the transfer endpoint, so retry safety is a cautious assumption rather than a complete end-to-end guarantee.
