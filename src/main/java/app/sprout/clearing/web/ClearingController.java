package app.sprout.clearing.web;

import app.sprout.clearing.config.ClearingProperties;
import app.sprout.clearing.config.ClearingProperties.Member;
import app.sprout.clearing.domain.ApiException;
import app.sprout.clearing.domain.Clearing;
import app.sprout.clearing.domain.Clearing.Client;
import app.sprout.clearing.domain.Clearing.Registered;
import app.sprout.clearing.domain.ErrorCode;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The members' API (clearing-v1.yaml): registering clients and following settlements. */
@RestController
public class ClearingController {

    public record RegisterRequest(String boId) {}

    private final Clearing clearing;
    private final ClearingProperties props;

    public ClearingController(Clearing clearing, ClearingProperties props) {
        this.clearing = clearing;
        this.props = props;
    }

    @PutMapping("/member/v1/clients/{clientCode}")
    public ResponseEntity<Map<String, Object>> register(@RequestHeader(value = "X-Member-Key", required = false) String key,
                                                        @PathVariable String clientCode, @RequestBody RegisterRequest req) {
        Registered r = clearing.register(clearing.member(key), clientCode, req.boId());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(client(r.client()));
    }

    @GetMapping("/member/v1/clients/{clientCode}")
    public Map<String, Object> client(@RequestHeader(value = "X-Member-Key", required = false) String key, @PathVariable String clientCode) {
        Member m = clearing.member(key);
        return client(clearing.client(m.name(), clientCode)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No client " + clientCode + " registered by you.")));
    }

    @GetMapping("/member/v1/settlements")
    public Map<String, Object> settlements(@RequestHeader(value = "X-Member-Key", required = false) String key) {
        Member m = clearing.member(key);
        return Map.of("settlements", clearing.settlements(m.name()).stream().map(s -> Clearing.dto(s, props.bank().vpa())).toList());
    }

    @GetMapping("/member/v1/settlements/{id}")
    public Map<String, Object> settlement(@RequestHeader(value = "X-Member-Key", required = false) String key, @PathVariable UUID id) {
        return Clearing.dto(clearing.mine(clearing.member(key), id), props.bank().vpa());
    }

    static Map<String, Object> client(Client c) {
        return Map.of("clientCode", c.clientCode(), "boId", c.boId(), "registeredAt", c.registeredAt().toString());
    }
}
