package app.sprout.clearing.domain;

import app.sprout.clearing.config.ClearingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The parties the clearing corporation deals with: market data (which session it is), the exchange
 * (the day's trades), the depository (shares) and Sprout Bank (money). Each call has a hard deadline
 * covering everything; no answer, or a 5xx, is {@link Unreachable} and the step is tried again later.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(5);

    public static class Unreachable extends RuntimeException {
        public Unreachable(String what, Throwable cause) {
            super(what, cause);
        }
    }

    public record Reply(int status, JsonNode body) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String code() {
            return body == null ? "" : body.path("code").asText();
        }
    }

    public record Trade(String member, String clientCode, String symbol, String side, long quantity, long pricePaise) {}

    private final ClearingProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Upstreams(ClearingProperties props, ObjectMapper json, Onward onward) {
        this.onward = onward;
        this.props = props;
        this.json = json;
    }

    /** The session the market is in now (or about to open, or just closed). */
    public LocalDate session() {
        Reply r = send("marketdata", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/market")).GET());
        if (!r.ok()) {
            throw new Unreachable("marketdata answered " + r.status(), null);
        }
        return LocalDate.parse(r.body().path("sessionDate").asText());
    }

    /** Every trade of a session, all pages. */
    public List<Trade> trades(LocalDate session) {
        List<Trade> all = new ArrayList<>();
        String after = null;
        while (true) {
            String url = props.exchange().url() + "/clearing/v1/trades?limit=1000&sessionDate=" + session + (after == null ? "" : "&after=" + after);
            Reply r = send("exchange", HttpRequest.newBuilder(URI.create(url)).header("X-Clearing-Key", props.exchange().key()).GET());
            if (!r.ok()) {
                throw new Unreachable("exchange answered " + r.status() + " " + r.code(), null);
            }
            JsonNode page = r.body().path("trades");
            for (JsonNode t : page) {
                all.add(new Trade(t.path("member").asText(), t.path("clientCode").asText(), t.path("symbol").asText(), t.path("side").asText(),
                        t.path("quantity").asLong(), Money.paise(t.path("price").asText())));
                after = t.path("tradeId").asText();
            }
            if (page.size() < 1000) {
                return all;
            }
        }
    }

    public Reply depositoryTransfer(String instructionId, String kind, String boId, String symbol, long quantity, String settlementRef) {
        Map<String, Object> body = Map.of("instructionId", instructionId, "kind", kind, "boId", boId, "symbol", symbol,
                "quantity", quantity, "settlementRef", settlementRef);
        return send("depository", HttpRequest.newBuilder(URI.create(props.depository().url() + "/clearing/v1/transfers"))
                .header("Content-Type", "application/json").header("X-Clearing-Key", props.depository().key())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    /** What arrived in the clearing corporation's bank account carrying this reference, from this account. */
    public long receivedFrom(String vpa, String reference) {
        Reply r = send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/transactions?reference="
                + URLEncoder.encode(reference, StandardCharsets.UTF_8))).header("X-Partner-Key", props.bank().partnerKey()).GET());
        if (!r.ok()) {
            throw new Unreachable("bank answered " + r.status(), null);
        }
        long total = 0;
        for (JsonNode t : r.body().path("transactions")) {
            if (t.path("direction").asText().equals("IN") && t.path("counterparty").asText().equals(vpa)) {
                total += Money.paise(t.path("amount").asText());
            }
        }
        return total;
    }

    public Reply pay(String payeeVpa, long paise, String reference) {
        Map<String, Object> body = Map.of("payeeVpa", payeeVpa, "amount", Money.rupees(paise), "reference", reference);
        return send("bank", HttpRequest.newBuilder(URI.create(props.bank().url() + "/partner/v1/payouts"))
                .header("Content-Type", "application/json").header("X-Partner-Key", props.bank().partnerKey())
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
    }

    private Reply send(String what, HttpRequest.Builder req) {
        onward.headers(req);
        try {
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode(), null);
            }
            JsonNode body = res.body() == null || res.body().isBlank() ? null : json.readTree(res.body());
            return new Reply(res.statusCode(), body);
        } catch (Unreachable e) {
            throw e;
        } catch (TimeoutException e) {
            throw new Unreachable(what + " didn't answer within " + DEADLINE.toMillis() + " ms", e);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw new Unreachable(what + " unreachable: " + cause.getClass().getSimpleName(), cause);
        }
    }

    String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
