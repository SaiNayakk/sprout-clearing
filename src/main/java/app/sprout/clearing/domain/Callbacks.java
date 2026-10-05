package app.sprout.clearing.domain;

import app.sprout.clearing.config.ClearingProperties;
import app.sprout.clearing.config.ClearingProperties.Member;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers settlement news from the outbox, signed, until each is acknowledged with a 2xx: 1 s,
 * 2 s, 4 s ... up to a minute apart. A member that was down gets every event when it comes back.
 * Also runs settlement: finds finished trade dates, and moves every unfinished settlement along.
 */
@Component
public class Callbacks {

    private static final Logger log = LoggerFactory.getLogger(Callbacks.class);

    private record Due(UUID id, String member, String url, String body, int attempts) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ClearingProperties props;
    private final Clearing clearing;
    private final Upstreams up;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public Callbacks(JdbcClient db, TransactionTemplate tx, Clock clock, ClearingProperties props, Clearing clearing, Upstreams up) {
        this.db = db;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.clearing = clearing;
        this.up = up;
    }

    @Scheduled(fixedDelayString = "${sprout.clearing.settle-every:5s}")
    public void settle() {
        try {
            clearing.discover(up.session());
            clearing.advanceAll();
        } catch (RuntimeException e) {
            log.warn("Settlement round didn't finish: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${sprout.clearing.delivery-check:1s}")
    public void deliver() {
        try {
            deliverDue();
        } catch (RuntimeException e) {
            log.warn("Couldn't deliver callbacks this time: {}", e.getMessage());
        }
    }

    /** Sends every callback that's due. Returns how many were acknowledged. */
    public int deliverDue() {
        List<Due> due = tx.execute(s -> db.sql("""
                        SELECT id, member, callback_url, body, attempts FROM outbox
                        WHERE delivered_at IS NULL AND next_attempt_at <= ?
                        ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED""")
                .param(Timestamp.from(clock.instant()))
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getString("member"), rs.getString("callback_url"),
                        rs.getString("body"), rs.getInt("attempts")))
                .list());
        int delivered = 0;
        for (Due d : due) {
            String error = send(d);
            Instant now = clock.instant();
            if (error == null) {
                db.sql("UPDATE outbox SET delivered_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ?")
                        .params(Timestamp.from(now), d.id()).update();
                delivered++;
            } else {
                long backoff = Math.min(60, 1L << Math.min(d.attempts(), 6));
                db.sql("UPDATE outbox SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ?")
                        .params(Timestamp.from(now.plusSeconds(backoff)), error, d.id()).update();
                log.warn("Callback to {} failed (attempt {}): {}; retrying in {} s", d.member(), d.attempts() + 1, error, backoff);
            }
        }
        return delivered;
    }

    private String send(Due d) {
        Member member = props.members().stream().filter(m -> m.name().equals(d.member())).findFirst().orElse(null);
        if (member == null) {
            return "unknown member " + d.member();
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(d.url())).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Clearing-Signature", "sha256=" + sign(member.webhookSecret(), d.body()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.body())).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 ? null : "HTTP " + status;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
    }

    /** HMAC-SHA256 of the body, hex: what the member recomputes to know the clearing corporation sent it. */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
