package app.sprout.clearing.domain;

import app.sprout.clearing.config.ClearingProperties;
import app.sprout.clearing.config.ClearingProperties.Member;
import app.sprout.clearing.domain.Upstreams.Reply;
import app.sprout.clearing.domain.Upstreams.Trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Netting and settling. Every step either completes and is recorded, or is tried again on the next
 * round: the depository and the bank are asked with fixed instruction ids and references, so asking
 * twice never moves anything twice. That makes a settlement safe to resume after any crash.
 */
@Service
public class Clearing {

    private static final Logger log = LoggerFactory.getLogger(Clearing.class);

    public record Client(String member, String clientCode, String boId, Instant registeredAt) {}

    public record Line(String clientCode, String symbol, long bought, long sold, long boughtPaise, long soldPaise, String status,
                       long shortQuantity, long closeOutPaise) {
        public long net() {
            return bought - sold;
        }
    }

    public record Settlement(UUID id, String member, LocalDate tradeDate, String status, long boughtPaise, long soldPaise,
                             long closeOutPaise, String fundsDirection, Long fundsPaise, String payReference, Instant createdAt,
                             Instant updatedAt, List<Line> lines) {}

    public record Registered(Client client, boolean created) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Upstreams up;
    private final ClearingProperties props;
    private final ObjectMapper json;

    public Clearing(JdbcClient db, TransactionTemplate tx, Clock clock, Upstreams up, ClearingProperties props, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.up = up;
        this.props = props;
        this.json = json;
    }

    // ── members and clients ──────────────────────────────────────────────────

    public Member member(String key) {
        if (key != null) {
            for (Member m : props.members()) {
                if (MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), m.key().getBytes(StandardCharsets.UTF_8))) {
                    return m;
                }
            }
        }
        throw new ApiException(ErrorCode.UNAUTHENTICATED, "Send a valid X-Member-Key.");
    }

    public Registered register(Member m, String clientCode, String boId) {
        if (clientCode == null || !clientCode.matches("[A-Za-z0-9-]{1,64}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "clientCode is 1 to 64 letters, digits or dashes.");
        }
        if (boId == null || !boId.matches("[0-9]{16}")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "boId is the 16-digit demat account id.");
        }
        Optional<Client> existing = client(m.name(), clientCode);
        if (existing.isEmpty()) {
            try {
                db.sql("INSERT INTO clients (member, client_code, bo_id, registered_at) VALUES (?, ?, ?, ?)")
                        .params(m.name(), clientCode, boId, ts(clock.instant())).update();
                return new Registered(client(m.name(), clientCode).orElseThrow(), true);
            } catch (DuplicateKeyException e) {
                existing = client(m.name(), clientCode);
            }
        }
        Client c = existing.orElseThrow();
        if (!c.boId().equals(boId)) {
            throw new ApiException(ErrorCode.CLIENT_CONFLICT, "Client " + clientCode + " is registered with a different demat account.");
        }
        return new Registered(c, false);
    }

    public Optional<Client> client(String member, String clientCode) {
        return db.sql("SELECT member, client_code, bo_id, registered_at FROM clients WHERE member = ? AND client_code = ?")
                .params(member, clientCode)
                .query((rs, n) -> new Client(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant())).optional();
    }

    // ── finding trade dates to settle ────────────────────────────────────────

    /**
     * Creates the settlements for every trade date before the current session not yet looked at.
     * A day is only finished once the next session has begun, so today's trades wait for tomorrow.
     * Returns how many settlements were created.
     */
    public int discover(LocalDate session) {
        db.sql("INSERT INTO cursor (id, next_date) VALUES (1, ?) ON CONFLICT DO NOTHING").param(session.minusDays(props.lookBackDays())).update();
        LocalDate next = db.sql("SELECT next_date FROM cursor WHERE id = 1").query(LocalDate.class).single();
        int created = 0;
        for (LocalDate d = next; d.isBefore(session); d = d.plusDays(1)) {
            List<Trade> trades = up.trades(d);
            LocalDate day = d;
            created += tx.execute(s -> {
                int n = net(day, trades);
                db.sql("UPDATE cursor SET next_date = ? WHERE id = 1").param(day.plusDays(1)).update();
                return n;
            });
        }
        return created;
    }

    private int net(LocalDate day, List<Trade> trades) {
        Map<String, Map<String, long[]>> byMember = new TreeMap<>();   // member -> client|symbol -> [bought, sold, boughtPaise, soldPaise]
        for (Trade t : trades) {
            long[] l = byMember.computeIfAbsent(t.member(), k -> new TreeMap<>())
                    .computeIfAbsent(t.clientCode() + "|" + t.symbol(), k -> new long[4]);
            long value = Math.multiplyExact(t.pricePaise(), t.quantity());
            if (t.side().equals("BUY")) {
                l[0] += t.quantity();
                l[2] += value;
            } else {
                l[1] += t.quantity();
                l[3] += value;
            }
        }
        int created = 0;
        for (var member : byMember.entrySet()) {
            if (props.members().stream().noneMatch(m -> m.name().equals(member.getKey()))) {
                log.warn("Trades on {} by {}, not a member here; not settled", day, member.getKey());
                continue;
            }
            UUID id = UUID.randomUUID();
            long bought = member.getValue().values().stream().mapToLong(l -> l[2]).sum();
            long sold = member.getValue().values().stream().mapToLong(l -> l[3]).sum();
            Instant now = clock.instant();
            int inserted = db.sql("""
                            INSERT INTO settlements (id, member, trade_date, status, bought_paise, sold_paise, pay_reference, created_at, updated_at)
                            VALUES (?, ?, ?, 'COMPUTED', ?, ?, ?, ?, ?) ON CONFLICT (member, trade_date) DO NOTHING""")
                    .params(id, member.getKey(), day, bought, sold, "scc-" + id, ts(now), ts(now)).update();
            if (inserted == 0) {
                continue;
            }
            for (var line : member.getValue().entrySet()) {
                String[] key = line.getKey().split("\\|", 2);
                long[] l = line.getValue();
                db.sql("""
                                INSERT INTO lines (settlement_id, client_code, symbol, bought, sold, bought_paise, sold_paise, status)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                        .params(id, key[0], key[1], l[0], l[1], l[2], l[3], l[0] == l[1] ? "NOTHING_TO_MOVE" : "PENDING").update();
            }
            created++;
            log.info("Settlement {} for {} on {}: bought {}, sold {}", id, member.getKey(), day, Money.rupees(bought), Money.rupees(sold));
        }
        return created;
    }

    // ── settling ─────────────────────────────────────────────────────────────

    /** Moves every unfinished settlement as far as it can go now. Returns how many reached SETTLED. */
    public int advanceAll() {
        List<UUID> open = db.sql("SELECT id FROM settlements WHERE status <> 'SETTLED' ORDER BY trade_date, created_at").query(UUID.class).list();
        int settled = 0;
        for (UUID id : open) {
            try {
                if (advance(id).status().equals("SETTLED")) {
                    settled++;
                }
            } catch (Upstreams.Unreachable e) {
                log.info("Settlement {} waits: {}", id, e.getMessage());
            }
        }
        return settled;
    }

    public Settlement advance(UUID id) {
        Settlement s = settlement(id);
        Member m = props.members().stream().filter(x -> x.name().equals(s.member())).findFirst().orElseThrow();
        String status = s.status();
        if (status.equals("COMPUTED")) {
            payIn(s, m);
            status = settlement(id).status();
        }
        if (status.equals("AWAITING_FUNDS")) {
            Settlement now = settlement(id);   // the obligation was only just worked out
            if (up.receivedFrom(m.bankVpa(), now.payReference()) >= now.fundsPaise()) {
                step(id, "AWAITING_FUNDS", "FUNDS_IN");
                status = "FUNDS_IN";
                log.info("Settlement {}: {} paid in ₹{}", id, now.member(), Money.rupees(now.fundsPaise()));
            }
        }
        if (status.equals("FUNDS_IN")) {
            Settlement now = settlement(id);
            if (now.fundsDirection().equals("RECEIVE")) {
                Reply paid = up.pay(m.bankVpa(), now.fundsPaise(), now.payReference());
                if (!paid.ok()) {
                    throw new IllegalStateException("the bank refused to pay " + s.member() + " for " + id + ": " + paid.status() + " " + paid.code());
                }
            }
            step(id, "FUNDS_IN", "FUNDS_OUT");
            status = "FUNDS_OUT";
        }
        if (status.equals("FUNDS_OUT")) {
            payOut(settlement(id), m);
        }
        return settlement(id);
    }

    /** Takes each net seller's shares into settlement, closes out the short ones, then works out and announces the funds. */
    private void payIn(Settlement s, Member m) {
        for (Line l : s.lines()) {
            if (!l.status().equals("PENDING") || l.net() >= 0) {
                continue;
            }
            long qty = -l.net();
            Optional<Client> c = client(s.member(), l.clientCode());
            Reply r = c.isEmpty() ? null : up.depositoryTransfer(instruction(s, "in", l), "PAY_IN", c.get().boId(), l.symbol(), qty, s.payReference());
            if (r != null && r.ok()) {
                line(s.id(), l, "PAID_IN", 0, 0);
            } else if (r == null || r.code().equals("INSUFFICIENT_SECURITIES") || r.status() == 404) {
                // short: closed out in cash at the close-out percentage above what the client sold at
                long closeOut = ceilDiv(l.soldPaise() * qty * (100 + props.closeOutPercent()), l.sold() * 100);
                line(s.id(), l, "SHORT", qty, closeOut);
                log.warn("Settlement {}: {} short {} {} ({}); closed out for ₹{}", s.id(), l.clientCode(), qty, l.symbol(),
                        r == null ? "no demat account registered" : r.code(), Money.rupees(closeOut));
            } else {
                throw new IllegalStateException("the depository refused pay-in " + instruction(s, "in", l) + ": " + r.status() + " " + r.code());
            }
        }
        tx.executeWithoutResult(t -> {
            Settlement now = lock(s.id());
            if (!now.status().equals("COMPUTED")) {
                return;
            }
            long closeOut = now.lines().stream().mapToLong(Line::closeOutPaise).sum();
            long net = now.soldPaise() - now.boughtPaise() - closeOut;   // positive: the member is owed
            String direction = net > 0 ? "RECEIVE" : net < 0 ? "PAY" : "NONE";
            String next = net < 0 ? "AWAITING_FUNDS" : "FUNDS_IN";
            db.sql("UPDATE settlements SET status = ?, close_out_paise = ?, funds_direction = ?, funds_paise = ?, updated_at = ? WHERE id = ?")
                    .params(next, closeOut, direction, Math.abs(net), ts(clock.instant()), s.id()).update();
            tell(m, "OBLIGATION", settlement(s.id()));
        });
    }

    private void payOut(Settlement s, Member m) {
        for (Line l : s.lines()) {
            if (!l.status().equals("PENDING") || l.net() <= 0) {
                continue;
            }
            Optional<Client> c = client(s.member(), l.clientCode());
            if (c.isEmpty()) {
                log.error("Settlement {}: {} bought {} {} but has no demat account registered; holding them back", s.id(), l.clientCode(),
                        l.net(), l.symbol());
                return;
            }
            Reply r = up.depositoryTransfer(instruction(s, "out", l), "PAY_OUT", c.get().boId(), l.symbol(), l.net(), s.payReference());
            if (!r.ok()) {
                throw new IllegalStateException("the depository refused pay-out " + instruction(s, "out", l) + ": " + r.status() + " " + r.code());
            }
            line(s.id(), l, "DELIVERED", 0, 0);
        }
        tx.executeWithoutResult(t -> {
            Settlement now = lock(s.id());
            if (now.status().equals("FUNDS_OUT")) {
                step(s.id(), "FUNDS_OUT", "SETTLED");
                tell(m, "SETTLED", settlement(s.id()));
                log.info("Settlement {} for {} on {} SETTLED", s.id(), s.member(), s.tradeDate());
            }
        });
    }

    private static String instruction(Settlement s, String leg, Line l) {
        return "scc:" + s.id() + ":" + leg + ":" + l.clientCode() + ":" + l.symbol();
    }

    private void line(UUID id, Line l, String status, long shortQty, long closeOut) {
        db.sql("UPDATE lines SET status = ?, short_quantity = ?, close_out_paise = ? WHERE settlement_id = ? AND client_code = ? AND symbol = ?")
                .params(status, shortQty, closeOut, id, l.clientCode(), l.symbol()).update();
    }

    private void step(UUID id, String from, String to) {
        db.sql("UPDATE settlements SET status = ?, updated_at = ? WHERE id = ? AND status = ?").params(to, ts(clock.instant()), id, from).update();
    }

    private void tell(Member m, String type, Settlement s) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("settlement", dto(s, props.bank().vpa()));
        event.put("occurredAt", clock.instant().toString());
        try {
            db.sql("INSERT INTO outbox (id, member, callback_url, body, created_at, next_attempt_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID(), m.name(), m.callbackUrl(), json.writeValueAsString(event), ts(clock.instant()), ts(clock.instant()))
                    .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── reading ──────────────────────────────────────────────────────────────

    public List<Settlement> settlements(String member) {
        return db.sql("SELECT id FROM settlements WHERE member = ? ORDER BY trade_date DESC LIMIT 50").param(member).query(UUID.class).list()
                .stream().map(this::settlement).toList();
    }

    public Settlement mine(Member m, UUID id) {
        Settlement s = settlementOrNull(id);
        if (s == null || !s.member().equals(m.name())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No settlement " + id + " of yours.");
        }
        return s;
    }

    public Settlement settlement(UUID id) {
        return Optional.ofNullable(settlementOrNull(id)).orElseThrow();
    }

    private Settlement settlementOrNull(UUID id) {
        return db.sql(SETTLEMENT_SQL + " WHERE id = ?").param(id).query(this::row).optional().orElse(null);
    }

    private Settlement lock(UUID id) {
        return db.sql(SETTLEMENT_SQL + " WHERE id = ? FOR UPDATE").param(id).query(this::row).single();
    }

    private static final String SETTLEMENT_SQL = """
            SELECT id, member, trade_date, status, bought_paise, sold_paise, close_out_paise, funds_direction, funds_paise, pay_reference,
                   created_at, updated_at FROM settlements""";

    private Settlement row(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Line> lines = db.sql("""
                        SELECT client_code, symbol, bought, sold, bought_paise, sold_paise, status, short_quantity, close_out_paise
                        FROM lines WHERE settlement_id = ? ORDER BY client_code, symbol""")
                .param(id)
                .query((r, i) -> new Line(r.getString(1), r.getString(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6),
                        r.getString(7), r.getLong(8), r.getLong(9)))
                .list();
        return new Settlement(id, rs.getString("member"), rs.getObject("trade_date", LocalDate.class), rs.getString("status"),
                rs.getLong("bought_paise"), rs.getLong("sold_paise"), rs.getLong("close_out_paise"), rs.getString("funds_direction"),
                rs.getObject("funds_paise", Long.class), rs.getString("pay_reference"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), lines);
    }

    /** A settlement as the contract shows it. */
    public static Map<String, Object> dto(Settlement s, String clearingVpa) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id().toString());
        m.put("member", s.member());
        m.put("tradeDate", s.tradeDate().toString());
        m.put("status", s.status());
        m.put("fundsDirection", s.fundsDirection() == null ? "NONE" : s.fundsDirection());
        m.put("fundsAmount", Money.rupees(s.fundsPaise() == null ? 0 : s.fundsPaise()));
        m.put("boughtValue", Money.rupees(s.boughtPaise()));
        m.put("soldValue", Money.rupees(s.soldPaise()));
        m.put("closeOutValue", Money.rupees(s.closeOutPaise()));
        if ("PAY".equals(s.fundsDirection())) {
            m.put("payTo", clearingVpa);
        }
        m.put("payReference", s.payReference());
        m.put("lines", s.lines().stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("clientCode", l.clientCode());
            line.put("symbol", l.symbol());
            line.put("bought", l.bought());
            line.put("sold", l.sold());
            line.put("net", l.net());
            line.put("boughtValue", Money.rupees(l.boughtPaise()));
            line.put("soldValue", Money.rupees(l.soldPaise()));
            line.put("status", l.status());
            if (l.shortQuantity() > 0) {
                line.put("shortQuantity", l.shortQuantity());
                line.put("closeOutValue", Money.rupees(l.closeOutPaise()));
            }
            return line;
        }).toList());
        m.put("createdAt", s.createdAt().toString());
        m.put("updatedAt", s.updatedAt().toString());
        return m;
    }

    static long ceilDiv(long a, long b) {
        return Math.floorDiv(a + b - 1, b);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
