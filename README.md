# sprout-clearing

The **Sprout Clearing Corporation**: a simulated clearing corporation (like NSE Clearing). It stands
between every buyer and seller on the [exchange](https://github.com/SaiNayakk/sprout-exchange) and
settles their trades on the next trading day (T+1), through the
[depository](https://github.com/SaiNayakk/sprout-depository) and [Sprout Bank](https://github.com/SaiNayakk/sprout-bank).

Once the next session has begun, it reads the day's trades and nets them: shares per client and share,
money per member. Then, each step recorded as it completes and resumed after any failure:

1. **Securities pay-in**: net sellers' shares into settlement. A client who doesn't hold what they sold is **short**, closed out in cash at 20% above their sale price, charged to the member.
2. **Obligation**: the member is told what it owes or is owed, line by line (signed callback).
3. **Funds pay-in**: a member that owes pays into the clearing corporation's bank account with the obligation's reference; it is seen in the bank statement.
4. **Funds pay-out**: a member that is owed is paid.
5. **Securities pay-out**: net buyers' shares are delivered, and the member is told it's settled.

The depository and the bank are always asked with fixed instruction ids and references, so a step tried
twice never moves anything twice. Its bank account opens with a float standing for the other
(simulated) members' money.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`clearing-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/clearing-v1.yaml)
in sprout-contracts. It runs inside the **street** host.

`./mvnw verify` runs the tests on a real Postgres (Docker needed) against stand-ins for market data, the
exchange, the depository, the bank and the member, every JSON response checked against the contract.

## License

MIT
