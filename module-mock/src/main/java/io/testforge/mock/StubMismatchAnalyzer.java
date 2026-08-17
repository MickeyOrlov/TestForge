package io.testforge.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.http.Cookie;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.BinaryEqualToPattern;
import com.github.tomakehurst.wiremock.matching.ContentPattern;
import com.github.tomakehurst.wiremock.matching.MatchResult;
import com.github.tomakehurst.wiremock.matching.MatchesJsonPathPattern;
import com.github.tomakehurst.wiremock.matching.MultiValuePattern;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.matching.UrlPattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class StubMismatchAnalyzer {

    private StubMismatchAnalyzer() {}

    static ObjectNode analyze(ObjectMapper mapper,
                              List<StubMapping> stubs,
                              LoggedRequest request,
                              String scopeJsonPath) {
        ObjectNode result = mapper.createObjectNode();

        if (stubs == null || stubs.isEmpty()) {
            result.putNull("closestStub");
            result.put("reason", "no scoped stubs registered");
            return result;
        }

        StubMapping bestStub = null;
        int bestIndex = -1;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < stubs.size(); i++) {
            StubMapping stub = stubs.get(i);
            if (stub == null || stub.getRequest() == null) {
                continue;
            }
            try {
                MatchResult matchResult = stub.getRequest().match(request);
                if (matchResult != null) {
                    double distance = matchResult.getDistance();
                    if (distance < minDistance) {
                        minDistance = distance;
                        bestIndex = i;
                        bestStub = stub;
                    }
                }
            } catch (Throwable ignored) {
                // Per-stub guard: a stub whose matcher throws is skipped for ranking
            }
        }

        if (bestStub == null) {
            // Fall back to CASE B on the first stub with an empty mismatches array
            bestStub = stubs.get(0);
            bestIndex = 0;
            minDistance = 1.0;
        }

        ObjectNode closestStubNode = mapper.createObjectNode();
        closestStubNode.put("stubIndex", bestIndex);
        if (bestStub.getId() != null) {
            closestStubNode.put("id", bestStub.getId().toString());
        }
        if (bestStub.getName() != null) {
            closestStubNode.put("name", bestStub.getName());
        }
        double roundedDistance = Math.round(minDistance * 10000.0) / 10000.0;
        closestStubNode.put("distance", roundedDistance);

        ArrayNode mismatches = mapper.createArrayNode();
        RequestPattern reqPattern = bestStub.getRequest();

        if (reqPattern != null) {
            // 1. method
            try {
                RequestMethod patternMethod = reqPattern.getMethod();
                if (patternMethod != null && patternMethod != RequestMethod.ANY) {
                    RequestMethod reqMethod = request != null ? request.getMethod() : null;
                    MatchResult mr = patternMethod.match(reqMethod);
                    if (mr == null || !mr.isExactMatch()) {
                        ObjectNode mNode = mismatches.addObject();
                        mNode.put("component", "method");
                        mNode.put("expected", patternMethod.getName());
                        mNode.put("actual", reqMethod != null ? reqMethod.getName() : null);
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2. url
            try {
                UrlPattern urlMatcher = reqPattern.getUrlMatcher();
                if (urlMatcher != null && urlMatcher.isSpecified()) {
                    String reqUrl = request != null ? request.getUrl() : null;
                    MatchResult mr = urlMatcher.match(reqUrl);
                    if (mr == null || !mr.isExactMatch()) {
                        ObjectNode uNode = mismatches.addObject();
                        uNode.put("component", "url");
                        uNode.put("matcher", urlMatcher.getName());
                        uNode.put("expected", urlMatcher.getExpected());
                        uNode.put("actual", reqUrl);
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3. body
            try {
                List<ContentPattern<?>> bodyPatterns = reqPattern.getBodyPatterns();
                if (bodyPatterns != null) {
                    for (ContentPattern<?> pattern : bodyPatterns) {
                        if (pattern == null) continue;
                        try {
                            boolean matches = checkBodyPatternMatch(pattern, request);
                            if (!matches) {
                                addBodyMismatch(mismatches, pattern, scopeJsonPath);
                            }
                        } catch (Throwable ignored) {
                            addBodyMismatch(mismatches, pattern, scopeJsonPath);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 4. header
            try {
                Map<String, MultiValuePattern> headers = reqPattern.getHeaders();
                if (headers != null && !headers.isEmpty()) {
                    List<String> sortedKeys = new ArrayList<>(headers.keySet());
                    Collections.sort(sortedKeys);
                    for (String key : sortedKeys) {
                        MultiValuePattern pattern = headers.get(key);
                        if (pattern == null) continue;
                        try {
                            HttpHeader header = request != null ? request.header(key) : null;
                            MatchResult mr = pattern.match(header);
                            if (mr == null || !mr.isExactMatch()) {
                                ObjectNode hNode = mismatches.addObject();
                                hNode.put("component", "header");
                                hNode.put("name", key);
                            }
                        } catch (Throwable ignored) {
                            ObjectNode hNode = mismatches.addObject();
                            hNode.put("component", "header");
                            hNode.put("name", key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 5. queryParam
            try {
                Map<String, MultiValuePattern> queryParams = reqPattern.getQueryParameters();
                if (queryParams != null && !queryParams.isEmpty()) {
                    List<String> sortedKeys = new ArrayList<>(queryParams.keySet());
                    Collections.sort(sortedKeys);
                    for (String key : sortedKeys) {
                        MultiValuePattern pattern = queryParams.get(key);
                        if (pattern == null) continue;
                        try {
                            QueryParameter qp = request != null ? request.queryParameter(key) : null;
                            MatchResult mr = pattern.match(qp);
                            if (mr == null || !mr.isExactMatch()) {
                                ObjectNode qNode = mismatches.addObject();
                                qNode.put("component", "queryParam");
                                qNode.put("name", key);
                            }
                        } catch (Throwable ignored) {
                            ObjectNode qNode = mismatches.addObject();
                            qNode.put("component", "queryParam");
                            qNode.put("name", key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 6. cookie
            try {
                Map<String, StringValuePattern> cookies = reqPattern.getCookies();
                if (cookies != null && !cookies.isEmpty()) {
                    List<String> sortedKeys = new ArrayList<>(cookies.keySet());
                    Collections.sort(sortedKeys);
                    for (String key : sortedKeys) {
                        StringValuePattern pattern = cookies.get(key);
                        if (pattern == null) continue;
                        try {
                            boolean matches = checkCookieMatch(pattern, request, key);
                            if (!matches) {
                                ObjectNode cNode = mismatches.addObject();
                                cNode.put("component", "cookie");
                                cNode.put("name", key);
                            }
                        } catch (Throwable ignored) {
                            ObjectNode cNode = mismatches.addObject();
                            cNode.put("component", "cookie");
                            cNode.put("name", key);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        closestStubNode.set("mismatches", mismatches);
        result.set("closestStub", closestStubNode);
        return result;
    }

    private static void addBodyMismatch(ArrayNode mismatches, ContentPattern<?> pattern, String scopeJsonPath) {
        ObjectNode bNode = mismatches.addObject();
        bNode.put("component", "body");
        bNode.put("matcher", pattern.getName());
        boolean isScopeMarker = pattern instanceof MatchesJsonPathPattern jsonPathPattern
                && scopeJsonPath != null
                && scopeJsonPath.equals(jsonPathPattern.getExpected());
        if (isScopeMarker) {
            bNode.put("jsonPath", scopeJsonPath);
        }
        bNode.put("scopeMarker", isScopeMarker);
    }

    private static boolean checkBodyPatternMatch(ContentPattern<?> pattern, LoggedRequest request) {
        if (request == null) {
            return false;
        }
        if (pattern instanceof StringValuePattern stringValuePattern) {
            String bodyStr = request.getBodyAsString();
            if (bodyStr != null && bodyStr.isEmpty()) {
                bodyStr = null;
            }
            MatchResult mr = stringValuePattern.match(bodyStr);
            return mr != null && mr.isExactMatch();
        } else if (pattern instanceof BinaryEqualToPattern binaryEqualToPattern) {
            MatchResult mr = binaryEqualToPattern.match(request.getBody());
            return mr != null && mr.isExactMatch();
        } else {
            @SuppressWarnings("unchecked")
            ContentPattern<Object> raw = (ContentPattern<Object>) pattern;
            MatchResult mr = raw.match(request.getBodyAsString());
            return mr != null && mr.isExactMatch();
        }
    }

    private static boolean checkCookieMatch(StringValuePattern pattern, LoggedRequest request, String key) {
        if (request == null) {
            return pattern.nullSafeIsAbsent();
        }
        Map<String, Cookie> reqCookies = request.getCookies();
        Cookie cookie = reqCookies != null ? reqCookies.get(key) : null;
        if (cookie == null || cookie.isAbsent()) {
            return pattern.nullSafeIsAbsent();
        }
        List<String> values = cookie.getValues();
        if (values == null || values.isEmpty()) {
            return pattern.nullSafeIsAbsent();
        }
        for (String val : values) {
            MatchResult mr = pattern.match(val);
            if (mr != null && mr.isExactMatch()) {
                return true;
            }
        }
        return false;
    }
}
