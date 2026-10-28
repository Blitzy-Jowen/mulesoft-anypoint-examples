/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Negotiates the response representation of a RAML action exactly as the APIkit 3.8.0 router did, so an
 * {@code Accept} header that gives the action's media type quality 0 gets APIkit's 406 mapping. For an action whose
 * success response declares a body, {@code HttpRestRequest} chose that body's media type with
 * {@code RestContentTypeParser.bestMatch} and raised {@code NotAcceptableException} when none was chosen; an absent
 * or blank {@code Accept} header was read as {@code *}{@code /*}.
 *
 * <p>The router checked a request in this order, and the first failure decided the answer: the resource (404), the
 * method (405), the URI parameters (400), the query parameters and headers (400), the request media type and body
 * (415, 400), then this output negotiation (406), and only then did the action's flow run. So a request with both an
 * invalid parameter and an unacceptable {@code Accept} header got the 400, and an unknown entity requested with an
 * unacceptable header got the 406, because the flow that would have raised the 404 never ran.
 *
 * <p>Spring MVC matches a mapping's {@code produces} by media type compatibility alone and ignores quality values,
 * so {@code application/json;q=0} selects a JSON handler, which would then answer 200. Spring binds, converts and
 * validates the handler's arguments only when it invokes the handler, and some RAML checks run in the handler's own
 * body, so no step ahead of the invocation sees the outcome of the parameter validation. Each RAML action that
 * declares {@code produces} therefore calls {@link #negotiate(HttpServletRequest)} itself, after its own HEAD
 * rejection and parameter checks and immediately before it calls its service, the original flow: the step is placed
 * where APIkit placed it. The call throws {@link HttpMediaTypeNotAcceptableException}, which the project's
 * {@code ApiKitExceptionHandler} answers with APIkit's 406 {@code { "message": "Not acceptable" }}, when
 * {@link #bestMatch} chooses none of the action's declared types. The declared types are the method's
 * {@code produces}, or the class's when the method declares none, in declaration order and without negated
 * ({@code !}) ones; an action that declares none is not negotiated.
 *
 * <p>Spring's own {@code produces} check still runs when the handler is selected, before any parameter is validated:
 * an {@code Accept} header incompatible with every declared type, such as {@code text/xml}, is answered with the
 * same 406 even when a parameter is also invalid, where APIkit, validating the parameter first, answered 400. That is
 * how the controllers answered such a header before this class existed, and this class does not change it.
 *
 * <p>{@link #bestMatch} is a port of APIkit's parser, with its quirks kept:
 * <ul>
 *   <li>the header is split at {@code ,} with empty tokens dropped, so a header of commas alone holds no range and
 *       nothing is chosen;</li>
 *   <li>the first declared type is weighed with quality {@code 1} and every later one with {@code 0.5};</li>
 *   <li>a range missing {@code q}, with a blank one, or one outside 0..1 counts as {@code q=1}, and a {@code q} that
 *       is not a number counts as 0;</li>
 *   <li>the range of highest fitness for a type sets its quality: 100 for an equal type, 10 for an equal subtype, 1
 *       per equal parameter other than {@code q}, the earlier range winning a tie, so in
 *       {@code application/json;q=0, *}{@code /*} the exact range decides;</li>
 *   <li>a {@code *}{@code /*} range (or {@code *}) lends a type the type's own weight rather than the range's
 *       {@code q}, so {@code *}{@code /*;q=0} still accepts the first declared type, as APIkit's router did;</li>
 *   <li>types, subtypes and parameter names are compared case-sensitively, so {@code APPLICATION/JSON} matches no
 *       declared type;</li>
 *   <li>the types are ordered by fitness, then quality, stably, the last one wins, and it is chosen unless its
 *       quality is 0.</li>
 * </ul>
 * APIkit failed with an unhandled error on a range without a type and subtype. Spring rejects an {@code Accept}
 * header holding such a range with its own 406 before any handler is selected, unless the range is blank, which it
 * skips; the port ignores such a range too, so {@code application/json, } is accepted. APIkit then also
 * required the chosen type, without parameters, to be a declared type; every declared {@code produces} value here is
 * a plain lower-case {@code type/subtype}, for which that check always holds, so it is not repeated.
 *
 * <p>The RAML-backed projects carry copies of this class that differ from this one in the {@code package} line
 * alone, so the class depends only on the JDK, the Servlet API and Spring.
 */
public final class ApiKitAcceptNegotiator {

    /** The header value APIkit negotiated against when a request had no {@code Accept} header or a blank one. */
    private static final String ANY = "*/*";

    private static final String QUALITY = "q";

    private ApiKitAcceptNegotiator() {
    }

    /**
     * Negotiates the response representation of the RAML action handling {@code request}, as APIkit did after it
     * validated the request and before it ran the action's flow. The action is the handler Spring MVC selected for
     * the request, which it records as {@link HandlerMapping#BEST_MATCHING_HANDLER_ATTRIBUTE}; an action that
     * declares no {@code produces} passes unchanged.
     *
     * @param request the request the calling action is handling, whose {@code Accept} header lines are read joined
     *                by {@code ,}
     * @throws HttpMediaTypeNotAcceptableException carrying the declared types, when none of them is chosen
     * @throws IllegalStateException               when the request names no {@link HandlerMethod} as its handler,
     *                                             because the caller is not an action of a Spring MVC dispatch
     */
    public static void negotiate(HttpServletRequest request) throws HttpMediaTypeNotAcceptableException {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            throw new IllegalStateException("No handler method selected for the request; negotiate(request) must be "
                    + "called by the action Spring MVC is invoking");
        }
        List<String> declared = declaredProduces(handlerMethod);
        if (declared.isEmpty()) {
            return;
        }
        if (bestMatch(declared, acceptHeader(request)) == null) {
            throw new HttpMediaTypeNotAcceptableException(MediaType.parseMediaTypes(declared));
        }
    }

    /**
     * Chooses the declared type the {@code Accept} header {@code header} prefers, as APIkit 3.8.0's
     * {@code RestContentTypeParser.bestMatch} chose it.
     *
     * @param supported the declared media types, in declaration order; not empty
     * @param header    the {@code Accept} header value, {@code *}{@code /*} when the request sent none; not
     *                  {@code null}
     * @return the chosen type as declared, or {@code null} when the request is not acceptable
     * @throws IllegalArgumentException when {@code supported} is empty, because APIkit negotiated only an action
     *                                  that declares a response type, or holds a type without type and subtype,
     *                                  which no mapping Spring registers can declare
     */
    static String bestMatch(List<String> supported, String header) {
        if (supported.isEmpty()) {
            throw new IllegalArgumentException("No declared media type to negotiate");
        }
        List<MediaRange> ranges = new ArrayList<>();
        for (String token : split(header, ',')) {
            MediaRange range = parseMediaRange(token);
            if (range != null) {
                ranges.add(range);
            }
        }
        List<FitnessAndQuality> weighted = new ArrayList<>(supported.size());
        String weight = "1";
        for (String type : supported) {
            weighted.add(fitnessAndQuality(type, type + ";" + QUALITY + "=" + weight, ranges));
            weight = "0.5";
        }
        // List.sort is stable, as the Collections.sort APIkit used: equal entries keep their declaration order.
        weighted.sort(null);
        FitnessAndQuality last = weighted.get(weighted.size() - 1);
        return Float.compare(last.quality, 0f) != 0 ? last.mimeType : null;
    }

    /**
     * The fitness and quality of the declared {@code mimeType}, weighed as {@code target}, against {@code ranges}:
     * fitness -1 and quality 0 when no range matches it.
     *
     * @throws IllegalArgumentException when {@code mimeType} has no type and subtype
     */
    private static FitnessAndQuality fitnessAndQuality(String mimeType, String target, List<MediaRange> ranges) {
        MediaRange weighed = parseMediaRange(target);
        if (weighed == null) {
            throw new IllegalArgumentException("Declared media type without type and subtype: " + mimeType);
        }
        int bestFitness = -1;
        float bestQuality = 0f;
        for (MediaRange range : ranges) {
            boolean typeMatches = weighed.type.equals(range.type) || range.type.equals("*")
                    || weighed.type.equals("*");
            boolean subTypeMatches = weighed.subType.equals(range.subType) || range.subType.equals("*")
                    || weighed.subType.equals("*");
            if (!typeMatches || !subTypeMatches) {
                continue;
            }
            for (Map.Entry<String, String> parameter : weighed.params.entrySet()) {
                String name = parameter.getKey();
                int paramMatches = !name.equals(QUALITY) && range.params.containsKey(name)
                        && parameter.getValue().equals(range.params.get(name)) ? 1 : 0;
                int fitness = (range.type.equals(weighed.type) ? 100 : 0)
                        + (range.subType.equals(weighed.subType) ? 10 : 0) + paramMatches;
                if (fitness > bestFitness) {
                    bestFitness = fitness;
                    bestQuality = range.type.equals("*") && range.subType.equals("*")
                            ? toFloat(weighed.params.get(QUALITY), 0f)
                            : toFloat(range.params.get(QUALITY), 0f);
                }
            }
        }
        return new FitnessAndQuality(bestFitness, bestQuality, mimeType);
    }

    /**
     * Parses one media range, its {@code q} replaced by {@code 1} when missing, blank or outside 0..1; a
     * {@code q} that is not a number is kept and later reads as 0.
     *
     * @return the range, or {@code null} when it has no type and subtype
     */
    private static MediaRange parseMediaRange(String range) {
        MediaRange parsed = parseMimeType(range);
        if (parsed == null) {
            return null;
        }
        String quality = parsed.params.get(QUALITY);
        float value = toFloat(quality, 1f);
        if (quality == null || quality.isBlank() || value < 0f || value > 1f) {
            parsed.params.put(QUALITY, "1");
        }
        return parsed;
    }

    /**
     * Parses {@code type/subtype;name=value...}: parameters are split at {@code ;} and {@code =}, empty pieces
     * dropped, and kept only as an exact name/value pair, trimmed, the later of two equal names winning; {@code *}
     * reads as {@code *}{@code /*}.
     *
     * @return the media type, or {@code null} when it has no type and subtype
     */
    private static MediaRange parseMimeType(String mimeType) {
        List<String> parts = split(mimeType, ';');
        if (parts.isEmpty()) {
            return null;
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < parts.size(); i++) {
            List<String> pair = split(parts.get(i), '=');
            if (pair.size() == 2) {
                params.put(pair.get(0).trim(), pair.get(1).trim());
            }
        }
        String fullType = parts.get(0).trim();
        if (fullType.equals("*")) {
            fullType = ANY;
        }
        List<String> types = split(fullType, '/');
        if (types.size() < 2) {
            return null;
        }
        return new MediaRange(types.get(0).trim(), types.get(1).trim(), params);
    }

    /**
     * Splits {@code text} at {@code separator} as commons-lang's {@code StringUtils.split} does: adjacent
     * separators count as one, empty tokens are dropped and tokens are not trimmed.
     */
    private static List<String> split(String text, char separator) {
        List<String> tokens = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == separator) {
                if (start >= 0) {
                    tokens.add(text.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            tokens.add(text.substring(start));
        }
        return tokens;
    }

    /** {@code value} as a float, or {@code fallback} when it is {@code null} or not a number. */
    private static float toFloat(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * The media types the handler declares in {@code produces}: the method's, or its class's when the method
     * declares none, in declaration order, without negated ({@code !}) expressions.
     */
    private static List<String> declaredProduces(HandlerMethod handlerMethod) {
        String[] produces = produces(
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequestMapping.class));
        if (produces.length == 0) {
            produces = produces(
                    AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequestMapping.class));
        }
        List<String> declared = new ArrayList<>(produces.length);
        for (String expression : produces) {
            String type = expression.trim();
            if (!type.isEmpty() && !type.startsWith("!")) {
                declared.add(type);
            }
        }
        return declared;
    }

    /** The {@code produces} of {@code mapping}, empty when there is no mapping. */
    private static String[] produces(RequestMapping mapping) {
        return mapping == null ? new String[0] : mapping.produces();
    }

    /**
     * Every {@code Accept} header line of {@code request} joined by {@code ,}, the single value APIkit read, or
     * {@code *}{@code /*} when that value is blank.
     */
    private static String acceptHeader(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders(HttpHeaders.ACCEPT);
        String header = lines == null ? "" : String.join(",", Collections.list(lines));
        return header.isBlank() ? ANY : header;
    }

    /** A parsed media range: type, subtype and parameters, names and values as written. */
    private static final class MediaRange {

        private final String type;

        private final String subType;

        private final Map<String, String> params;

        /**
         * @param type    the type, {@code *} for any
         * @param subType the subtype, {@code *} for any
         * @param params  the parameters, {@code q} included; mutable
         */
        MediaRange(String type, String subType, Map<String, String> params) {
            this.type = type;
            this.subType = subType;
            this.params = params;
        }
    }

    /**
     * A declared type with its best fitness and quality, ordered by fitness and then quality, as APIkit ordered
     * them.
     */
    private static final class FitnessAndQuality implements Comparable<FitnessAndQuality> {

        private final int fitness;

        private final float quality;

        private final String mimeType;

        /**
         * @param fitness  the best fitness of a range for the type, -1 when none matches
         * @param quality  the quality that range lends the type
         * @param mimeType the type as declared
         */
        FitnessAndQuality(int fitness, float quality, String mimeType) {
            this.fitness = fitness;
            this.quality = quality;
            this.mimeType = mimeType;
        }

        /**
         * Orders by fitness, then by quality, with APIkit's {@code ==} and {@code <} comparisons.
         *
         * @param other the entry compared with
         * @return a negative number, zero or a positive number as this entry ranks below, with or above
         *         {@code other}
         */
        @Override
        public int compareTo(FitnessAndQuality other) {
            if (fitness == other.fitness) {
                if (quality == other.quality) {
                    return 0;
                }
                return quality < other.quality ? -1 : 1;
            }
            return fitness < other.fitness ? -1 : 1;
        }
    }
}
