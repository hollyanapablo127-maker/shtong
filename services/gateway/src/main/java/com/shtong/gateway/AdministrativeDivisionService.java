package com.shtong.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AdministrativeDivisionService {
    private static final Logger LOG = LoggerFactory.getLogger(AdministrativeDivisionService.class);
    private static final Pattern CODE = Pattern.compile("^\\d{12}$");
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final String USER_AGENT = "Shtong-Administrative-Division/1.0";
    private final ObjectMapper json;
    private final HttpClient http;
    private final String endpoint;
    private final Duration cacheTtl;
    private final String userAgent;
    private final Map<String, CachedNode> cache = new ConcurrentHashMap<>();

    @Autowired
    public AdministrativeDivisionService(ObjectMapper json,
            @Value("${shtong.administrative-division-url:https://dmfw.mca.gov.cn/9095/xzqh/getList}") String endpoint) {
        this(json, endpoint, ReferenceDataHttpClientFactory.create(), CACHE_TTL, USER_AGENT);
    }

    AdministrativeDivisionService(ObjectMapper json, String endpoint, HttpClient http) {
        this(json, endpoint, http, CACHE_TTL, USER_AGENT);
    }

    AdministrativeDivisionService(ObjectMapper json, String endpoint, HttpClient http, Duration cacheTtl) {
        this(json, endpoint, http, cacheTtl, USER_AGENT);
    }

    private AdministrativeDivisionService(ObjectMapper json, String endpoint, HttpClient http,
            Duration cacheTtl, String userAgent) {
        this.json = json;
        this.endpoint = endpoint;
        this.http = http;
        this.cacheTtl = cacheTtl;
        this.userAgent = userAgent;
    }

    public List<Division> list(String targetLevel, String parentCode) {
        String level = Objects.requireNonNullElse(targetLevel, "").trim().toUpperCase();
        return switch (level) {
            case "PROVINCE" -> {
                if (parentCode != null && !parentCode.isBlank()) throw validation();
                yield children(fetch(null));
            }
            case "CITY" -> cities(requiredCode(parentCode));
            case "DISTRICT" -> districts(requiredCode(parentCode));
            default -> throw validation();
        };
    }

    public ResolvedAddress resolve(String provinceCode, String cityCode, String districtCode, String detail) {
        String province = requiredCode(provinceCode), city = requiredCode(cityCode), district = requiredCode(districtCode);
        String normalizedDetail = Objects.requireNonNullElse(detail, "").trim();
        if (normalizedDetail.length() < 2 || normalizedDetail.length() > 200) throw validation();
        Division provinceValue = find(list("PROVINCE", null), province);
        Division cityValue = find(list("CITY", province), city);
        Division districtValue = find(list("DISTRICT", city), district);
        return new ResolvedAddress(provinceValue.code(), provinceValue.name(), cityValue.code(), cityValue.name(),
                districtValue.code(), districtValue.name(), normalizedDetail);
    }

    private List<Division> cities(String provinceCode) {
        DivisionNode province = fetch(provinceCode);
        List<Division> values = province.children().stream().filter(child -> child.level() == 2).map(this::view).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (province.children().stream().anyMatch(child -> child.level() == 3)) {
            String name = province.type().contains("直辖市") ? province.name() : province.name() + "直辖县级行政区划";
            values.addFirst(new Division(province.code(), name));
        }
        return sorted(values);
    }

    private List<Division> districts(String parentCode) {
        DivisionNode parent = fetch(parentCode);
        return sorted(parent.children().stream().filter(child -> child.level() == 3).map(this::view).toList());
    }

    private Division find(List<Division> values, String code) {
        return values.stream().filter(value -> value.code().equals(code)).findFirst().orElseThrow(AdministrativeDivisionService::validation);
    }

    private List<Division> children(DivisionNode parent) {
        return sorted(parent.children().stream().map(this::view).filter(value -> CODE.matcher(value.code()).matches()).toList());
    }

    private List<Division> sorted(List<Division> values) {
        return values.stream().distinct().sorted(Comparator.comparing(Division::code)).toList();
    }

    private Division view(DivisionNode node) { return new Division(node.code(), node.name()); }

    private DivisionNode fetch(String code) {
        String key = code == null ? "ROOT" : requiredCode(code);
        CachedNode cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value();
        try {
            String query = code == null ? "maxLevel=1" : "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&maxLevel=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                    .timeout(Duration.ofSeconds(12))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Administrative division upstream returned HTTP " + response.statusCode());
            }
            JsonNode root = json.readTree(response.body());
            if (root.path("status").asInt() != 200 || !root.path("data").isObject()) {
                throw new IllegalStateException("Administrative division upstream returned an invalid response");
            }
            DivisionNode parsed = parse(root.path("data"));
            cache.put(key, new CachedNode(parsed, Instant.now().plus(cacheTtl)));
            return parsed;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return fallbackOrUnavailable(key, cached, failure);
        } catch (Exception failure) {
            return fallbackOrUnavailable(key, cached, failure);
        }
    }

    private DivisionNode fallbackOrUnavailable(String key, CachedNode cached, Exception failure) {
        if (cached != null) {
            LOG.warn("Administrative division upstream failed for {}; using stale cache: {}", key, failure.toString());
            return cached.value();
        }
        LOG.warn("Administrative division upstream failed for {}: {}", key, failure.toString());
        throw unavailable();
    }

    private DivisionNode parse(JsonNode node) {
        List<DivisionNode> children = new ArrayList<>();
        if (node.path("children").isArray()) for (JsonNode child : node.path("children")) children.add(parse(child));
        return new DivisionNode(node.path("code").asText(), node.path("name").asText(), node.path("level").asInt(), node.path("type").asText(), List.copyOf(children));
    }

    private static String requiredCode(String value) {
        String code = Objects.requireNonNullElse(value, "").trim();
        if (!CODE.matcher(code).matches()) throw validation();
        return code;
    }

    private static ApiError validation() { return new ApiError(400, "VALIDATION_FAILED", "error.validation.failed"); }
    private static ApiError unavailable() { return new ApiError(503, "REFERENCE_DATA_UNAVAILABLE", "error.referenceData.unavailable"); }

    public record Division(String code, String name) {}
    public record ResolvedAddress(String provinceCode, String provinceName, String cityCode, String cityName,
                                  String districtCode, String districtName, String detail) {}
    private record DivisionNode(String code, String name, int level, String type, List<DivisionNode> children) {}
    private record CachedNode(DivisionNode value, Instant expiresAt) {}
}
