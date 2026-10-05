package app.sprout.clearing.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.clearing} in clearing.yml. */
@ConfigurationProperties("sprout.clearing")
public record ClearingProperties(
        Duration settleEvery,
        int lookBackDays,
        int closeOutPercent,
        Duration deliveryCheck,
        String marketdataUrl,
        Keyed exchange,
        Keyed depository,
        Bank bank,
        List<Member> members) {

    public record Keyed(String url, String key) {}

    public record Bank(String url, String partnerKey, String vpa) {}

    /** A broker whose trades settle here: its key, bank account, and where and how its callbacks go. */
    public record Member(String name, String key, String bankVpa, String callbackUrl, String webhookSecret) {}
}
