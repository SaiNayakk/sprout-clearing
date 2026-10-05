package app.sprout.clearing;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.clearing.domain.Callbacks;
import app.sprout.clearing.domain.Clearing;
import app.sprout.contracts.Contracts;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The clearing corporation on a real Postgres, against stand-ins for market data (which session it
 * is), the exchange's trade tape, the depository, the bank and the member's callback. Each test
 * trades on its own day, so settlements don't mix.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=clearing", "sprout.clearing.settle-every=1h", "sprout.clearing.delivery-check=1h"})
@AutoConfigureMockMvc
class ClearingApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String KEY = "dev-only-clearing-member-key";

    static final AtomicReference<LocalDate> SESSION = new AtomicReference<>(LocalDate.parse("2026-09-01"));
    static final Map<LocalDate, List<Map<String, Object>>> TRADES = new ConcurrentHashMap<>();
    static final Map<String, Long> DEMAT = new ConcurrentHashMap<>();              // boId|symbol -> quantity
    static final Map<String, String> INSTRUCTIONS = new ConcurrentHashMap<>();     // instructionId -> body
    static final AtomicBoolean DEPOSITORY_DOWN = new AtomicBoolean();
    static final List<Map<String, Object>> BANK_IN = new CopyOnWriteArrayList<>(); // what reached clearing@sproutbank
    static final Map<String, Long> PAYOUTS = new ConcurrentHashMap<>();            // reference -> paise paid to the member
    static final List<JsonNode> EVENTS = new CopyOnWriteArrayList<>();
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=clearing");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.clearing.marketdata-url", () -> base);
        r.add("sprout.clearing.exchange.url", () -> base);
        r.add("sprout.clearing.depository.url", () -> base);
        r.add("sprout.clearing.bank.url", () -> base);
        r.add("sprout.clearing.members[0].name", () -> "sprout");
        r.add("sprout.clearing.members[0].key", () -> KEY);
        r.add("sprout.clearing.members[0].bank-vpa", () -> "sprout@sproutbank");
        r.add("sprout.clearing.members[0].callback-url", () -> base + "/events");
        r.add("sprout.clearing.members[0].webhook-secret", () -> "dev-only-clearing-webhook-secret");
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-06T04:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.CLEARING_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired Clearing clearing;
    @Autowired Callbacks callbacks;
    @Autowired MutableClock clock;

    LocalDate day;
    String asha;
    String ravi;

    @BeforeEach
    void aNewTradingDayWithTwoClients() throws Exception {
        DEPOSITORY_DOWN.set(false);
        day = SESSION.get().plusDays(2);
        SESSION.set(day);
        clearing.discover(day);   // everything before this day is looked at; this day isn't finished yet
        asha = "asha-" + UUID.randomUUID().toString().substring(0, 8);
        ravi = "ravi-" + UUID.randomUUID().toString().substring(0, 8);
        register(asha, bo()).andExpect(status().isCreated());
        register(ravi, bo()).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(5));
        callbacks.deliverDue();
        EVENTS.clear();
    }

    static String bo() {
        return "12081600" + String.format("%08d", (long) (Math.random() * 99_999_999L));
    }

    ResultActions register(String client, String boId) throws Exception {
        return mvc.perform(put("/member/v1/clients/" + client).header("X-Member-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("boId", boId))));
    }

    String boOf(String client) throws Exception {
        return JSON.readTree(mvc.perform(get("/member/v1/clients/" + client).header("X-Member-Key", KEY)).andReturn().getResponse()
                .getContentAsString()).path("boId").asText();
    }

    void trade(String client, String symbol, String side, long qty, String price) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tradeId", UUID.randomUUID().toString());
        t.put("member", "sprout");
        t.put("clientCode", client);
        t.put("symbol", symbol);
        t.put("side", side);
        t.put("quantity", qty);
        t.put("price", price);
        t.put("sessionDate", day.toString());
        t.put("executedAt", Instant.now().toString());
        TRADES.computeIfAbsent(day, d -> new CopyOnWriteArrayList<>()).add(t);
    }

    void holds(String client, String symbol, long qty) throws Exception {
        DEMAT.put(boOf(client) + "|" + symbol, qty);
    }

    long demat(String client, String symbol) throws Exception {
        return DEMAT.getOrDefault(boOf(client) + "|" + symbol, 0L);
    }

    /** The next session begins: the day's trades are final and settle. */
    JsonNode nextSession() throws Exception {
        SESSION.set(day.plusDays(1));
        clearing.discover(SESSION.get());
        return settlementFor(day);
    }

    JsonNode settlementFor(LocalDate d) throws Exception {
        for (JsonNode s : JSON.readTree(mvc.perform(get("/member/v1/settlements").header("X-Member-Key", KEY)).andExpect(MATCHES_CONTRACT)
                .andReturn().getResponse().getContentAsString()).path("settlements")) {
            if (s.path("tradeDate").asText().equals(d.toString())) {
                return s;
            }
        }
        return null;
    }

    JsonNode advance(JsonNode s) throws Exception {
        clearing.advanceAll();
        clock.advance(Duration.ofMinutes(2));
        callbacks.deliverDue();
        return JSON.readTree(mvc.perform(get("/member/v1/settlements/" + s.path("id").asText()).header("X-Member-Key", KEY))
                .andExpect(status().isOk()).andExpect(MATCHES_CONTRACT).andReturn().getResponse().getContentAsString());
    }

    static JsonNode line(JsonNode s, String client, String symbol) {
        for (JsonNode l : s.path("lines")) {
            if (l.path("clientCode").asText().equals(client) && l.path("symbol").asText().equals(symbol)) {
                return l;
            }
        }
        return null;
    }

    // ── settling ─────────────────────────────────────────────────────────────

    @Test
    void aDayThatOwesIsPaidInThenSharesGoBothWays() throws Exception {
        holds(ravi, "INKWELL", 10);
        trade(asha, "HARBOR", "BUY", 10, "1000.00");       // asha pays 10,000
        trade(ravi, "INKWELL", "SELL", 6, "200.00");       // ravi gets 1,200
        assertThat(settlementFor(day)).as("not settled while the day is still trading").isNull();
        JsonNode s = nextSession();
        assertThat(s.path("status").asText()).isEqualTo("COMPUTED");

        s = advance(s);
        assertThat(s.path("status").asText()).isEqualTo("AWAITING_FUNDS");
        assertThat(s.path("fundsDirection").asText()).isEqualTo("PAY");
        assertThat(s.path("fundsAmount").asText()).isEqualTo("8800.00");
        assertThat(s.path("payTo").asText()).isEqualTo("clearing@sproutbank");
        assertThat(demat(ravi, "INKWELL")).as("the seller's shares are in").isEqualTo(4);
        assertThat(line(s, ravi, "INKWELL").path("status").asText()).isEqualTo("PAID_IN");
        assertThat(EVENTS).extracting(e -> e.path("type").asText()).containsExactly("OBLIGATION");

        s = advance(s);
        assertThat(s.path("status").asText()).as("waits for the money").isEqualTo("AWAITING_FUNDS");
        assertThat(demat(asha, "HARBOR")).as("no shares before the money").isZero();
        BANK_IN.add(Map.of("from", "sprout@sproutbank", "reference", s.path("payReference").asText(), "paise", 8800_00L));
        s = advance(s);
        assertThat(s.path("status").asText()).isEqualTo("SETTLED");
        assertThat(demat(asha, "HARBOR")).isEqualTo(10);
        assertThat(line(s, asha, "HARBOR").path("status").asText()).isEqualTo("DELIVERED");
        assertThat(EVENTS).extracting(e -> e.path("type").asText()).containsExactly("OBLIGATION", "SETTLED");
        assertThat(PAYOUTS).doesNotContainKey(s.path("payReference").asText());
    }

    @Test
    void aDayThatIsOwedIsPaidOutByTheClearingCorporation() throws Exception {
        holds(ravi, "KOSHA", 50);
        trade(ravi, "KOSHA", "SELL", 50, "310.40");
        trade(asha, "HARBOR", "BUY", 1, "999.95");
        JsonNode s = advance(nextSession());
        assertThat(s.path("status").asText()).isEqualTo("SETTLED");
        assertThat(s.path("fundsDirection").asText()).isEqualTo("RECEIVE");
        assertThat(s.path("fundsAmount").asText()).isEqualTo("14520.05");
        assertThat(PAYOUTS.get(s.path("payReference").asText())).isEqualTo(14520_05L);
        assertThat(demat(asha, "HARBOR")).isEqualTo(1);
        assertThat(EVENTS).extracting(e -> e.path("type").asText()).containsExactly("OBLIGATION", "SETTLED");
    }

    @Test
    void anIntradayRoundTripMovesNoSharesAndOnlyTheProfit() throws Exception {
        trade(asha, "HARBOR", "BUY", 20, "1000.00");
        trade(asha, "HARBOR", "SELL", 20, "1012.50");
        JsonNode s = advance(nextSession());
        assertThat(line(s, asha, "HARBOR").path("status").asText()).isEqualTo("NOTHING_TO_MOVE");
        assertThat(s.path("fundsDirection").asText()).isEqualTo("RECEIVE");
        assertThat(s.path("fundsAmount").asText()).isEqualTo("250.00");
        assertThat(s.path("status").asText()).isEqualTo("SETTLED");
    }

    @Test
    void aSellerWhoDoesntHoldTheSharesIsShortAndClosedOutInCash() throws Exception {
        holds(ravi, "INKWELL", 3);
        trade(ravi, "INKWELL", "SELL", 5, "200.00");       // sold 5, holds 3
        JsonNode s = advance(nextSession());
        JsonNode l = line(s, ravi, "INKWELL");
        assertThat(l.path("status").asText()).isEqualTo("SHORT");
        assertThat(l.path("shortQuantity").asLong()).isEqualTo(5);
        assertThat(l.path("closeOutValue").asText()).isEqualTo("1200.00");   // 5 x 200 x 1.2
        assertThat(demat(ravi, "INKWELL")).as("all or nothing: nothing taken").isEqualTo(3);
        assertThat(s.path("fundsDirection").asText()).isEqualTo("PAY");
        assertThat(s.path("fundsAmount").asText()).isEqualTo("200.00");      // owed 1,000 for the sale, charged 1,200
    }

    @Test
    void aDepositoryOutageDelaysSettlementButNeverDoublesIt() throws Exception {
        holds(ravi, "KOSHA", 9);
        trade(ravi, "KOSHA", "SELL", 9, "300.00");
        trade(asha, "INKWELL", "BUY", 4, "200.00");
        DEPOSITORY_DOWN.set(true);
        JsonNode s = advance(nextSession());
        assertThat(s.path("status").asText()).isEqualTo("COMPUTED");
        DEPOSITORY_DOWN.set(false);
        s = advance(s);
        s = advance(s);
        assertThat(s.path("status").asText()).isEqualTo("SETTLED");
        assertThat(demat(ravi, "KOSHA")).isZero();
        assertThat(demat(asha, "INKWELL")).isEqualTo(4);
        assertThat(PAYOUTS.get(s.path("payReference").asText())).isEqualTo(1900_00L);
        advance(s);
        assertThat(demat(asha, "INKWELL")).as("advancing again changes nothing").isEqualTo(4);
        assertThat(EVENTS.stream().filter(e -> e.path("type").asText().equals("SETTLED")).count()).isEqualTo(1);
    }

    @Test
    void eachDaySettlesOnceAndOnlyAfterItEnds() throws Exception {
        trade(asha, "HARBOR", "BUY", 1, "1000.00");
        clearing.discover(day);
        assertThat(settlementFor(day)).isNull();
        JsonNode s = nextSession();
        clearing.discover(SESSION.get());
        clearing.discover(SESSION.get().plusDays(3));   // later days, no trades: nothing more
        long forDay = 0;
        for (JsonNode x : JSON.readTree(mvc.perform(get("/member/v1/settlements").header("X-Member-Key", KEY)).andReturn().getResponse()
                .getContentAsString()).path("settlements")) {
            forDay += x.path("tradeDate").asText().equals(day.toString()) ? 1 : 0;
        }
        assertThat(forDay).isEqualTo(1);
        assertThat(s.path("lines").size()).isEqualTo(1);
    }

    // ── clients ──────────────────────────────────────────────────────────────

    @Test
    void clientsAreRegisteredOnceWithOneDematAccount() throws Exception {
        String bo = boOf(asha);
        register(asha, bo).andExpect(status().isOk()).andExpect(MATCHES_CONTRACT);
        register(asha, bo()).andExpect(status().isConflict()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("CLIENT_CONFLICT"));
        register("bad code!", bo).andExpect(status().isBadRequest());
        mvc.perform(get("/member/v1/clients/" + asha).header("X-Member-Key", "nope")).andExpect(status().isUnauthorized()).andExpect(MATCHES_CONTRACT);
        mvc.perform(get("/member/v1/clients/nobody").header("X-Member-Key", KEY)).andExpect(status().isNotFound());
        mvc.perform(get("/member/v1/settlements/" + UUID.randomUUID()).header("X-Member-Key", KEY)).andExpect(status().isNotFound());
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", "OPEN", "sessionDate", SESSION.get().toString())));
            s.createContext("/clearing/v1/trades", ex -> {
                Map<String, String> q = query(ex);
                List<Map<String, Object>> all = TRADES.getOrDefault(LocalDate.parse(q.get("sessionDate")), List.of());
                int from = 0;
                if (q.containsKey("after")) {
                    for (int i = 0; i < all.size(); i++) {
                        if (all.get(i).get("tradeId").equals(q.get("after"))) {
                            from = i + 1;
                        }
                    }
                }
                int limit = Integer.parseInt(q.getOrDefault("limit", "500"));
                reply(ex, 200, Map.of("trades", all.subList(from, Math.min(all.size(), from + limit))));
            });
            s.createContext("/clearing/v1/transfers", ClearingApiTest::depository);
            s.createContext("/partner/v1/transactions", ex -> {
                String ref = query(ex).get("reference");
                List<Map<String, Object>> txns = new ArrayList<>();
                for (Map<String, Object> in : BANK_IN) {
                    if (in.get("reference").equals(ref)) {
                        long p = (Long) in.get("paise");
                        txns.add(Map.of("id", UUID.randomUUID().toString(), "direction", "IN", "amount", rupees(p), "description", "In",
                                "counterparty", in.get("from"), "reference", ref, "balanceAfter", "0.00", "at", Instant.now().toString()));
                    }
                }
                reply(ex, 200, Map.of("transactions", txns));
            });
            s.createContext("/partner/v1/payouts", ex -> {
                JsonNode p = JSON.readTree(ex.getRequestBody().readAllBytes());
                long paise = paise(p.path("amount").asText());
                boolean fresh = PAYOUTS.putIfAbsent(p.path("reference").asText(), paise) == null;
                reply(ex, fresh ? 201 : 200, Map.of("id", UUID.randomUUID().toString(), "status", "COMPLETED"));
            });
            s.createContext("/events", ex -> {
                EVENTS.add(JSON.readTree(ex.getRequestBody().readAllBytes()));
                reply(ex, 204, null);
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static synchronized void depository(HttpExchange ex) throws IOException {
        if (DEPOSITORY_DOWN.get()) {
            reply(ex, 503, Map.of());
            return;
        }
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode t = JSON.readTree(raw);
        String id = t.path("instructionId").asText();
        if (INSTRUCTIONS.containsKey(id)) {
            reply(ex, 200, JSON.readValue(raw, Map.class));
            return;
        }
        String key = t.path("boId").asText() + "|" + t.path("symbol").asText();
        long qty = t.path("quantity").asLong();
        long held = DEMAT.getOrDefault(key, 0L);
        if (t.path("kind").asText().equals("PAY_IN")) {
            if (held < qty) {
                reply(ex, 422, Map.of("code", "INSUFFICIENT_SECURITIES"));
                return;
            }
            DEMAT.put(key, held - qty);
        } else {
            DEMAT.put(key, held + qty);
        }
        INSTRUCTIONS.put(id, raw);
        reply(ex, 201, JSON.readValue(raw, Map.class));
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                String[] p = kv.split("=", 2);
                q.put(p[0], p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            }
        }
        return q;
    }

    static String rupees(long p) {
        return p / 100 + "." + String.format("%02d", p % 100);
    }

    static long paise(String r) {
        return Long.parseLong(r.replace(".", ""));
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
