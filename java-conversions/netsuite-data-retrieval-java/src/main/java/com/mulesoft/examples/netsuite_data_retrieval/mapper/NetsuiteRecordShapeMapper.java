/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.mulesoft.examples.netsuite_data_retrieval.config.NetsuiteProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rebuilds, from a NetSuite REST record, the SuiteTalk record the Mule NetSuite connector returned to
 * {@code payload map $} (AAP 0.7.4). The original flows of
 * {@code netsuite-data-retrieval/src/main/app/netsuite-api.xml} ran {@code netsuite:search} over SuiteTalk SOAP and
 * DataWeave wrote the returned record beans as JSON; the conversion reads REST records (SC-16) instead, so this
 * class owns the record shape behind
 * {@code CustomerSearchMapper}, {@code ItemSupplyPlanMapper} and {@code OpportunitySearchMapper} (AAP 0.5.8), and
 * {@link DwJsonWriter} owns the bytes. The parity targets are the committed examples
 * {@code src/main/resources/api/customers-response.json}, {@code items-response.json} and
 * {@code opportunities-response.json}.
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li>{@code restRecord}: a record of {@code GET /services/rest/record/v1/<type>/<id>?expandSubResources=true}
 *       parsed into maps, lists, strings, booleans and numbers (integers as {@code Integer}/{@code Long}/
 *       {@code BigInteger}, decimals as {@code BigDecimal}). It is never modified.</li>
 *   <li>{@code catalog}: the JSON Schema of {@code GET /services/rest/record/v1/metadata-catalog/<type>}. Field
 *       schemas are read at {@code properties.<restFieldId>}: their {@code type} ({@code boolean}, {@code integer},
 *       {@code number}, {@code string}, {@code object}, {@code array}) or {@code $ref}, their {@code format}
 *       ({@code date}, {@code date-time}), an {@code enum} keyword on the field or on its {@code properties.id}, and
 *       {@code properties.items} marking a multi-select. {@code null} reads as an empty catalog.</li>
 *   <li>{@code sourceIds}: custom select script ID → select source ID, as
 *       {@code NetsuiteRestClient.customFieldSourceIds} resolved it (override first, then the {@code customfield}
 *       lookup). {@code null} reads as empty.</li>
 * </ul>
 *
 * <h2>Field rules</h2>
 * <ul>
 *   <li>REST {@code id} becomes {@code internalId} (text); root {@code links} and {@code refName} are dropped; a
 *       null or absent field produces no key.</li>
 *   <li>For customers and opportunities a non-custom root field is written only when the catalog declares it,
 *       under its REST field id, which is the SuiteTalk name. Lists, maps holding {@code items} (sublists) and maps
 *       without {@code id} (subrecords) are omitted, except the opportunity {@code billingAddress} and
 *       {@code shippingAddress}.</li>
 *   <li>Numbers lose trailing zeros and are written as {@code BigInteger} when integral ({@code 100.0} →
 *       {@code 100}), otherwise as {@code BigDecimal} ({@code -32.3}); booleans stay native; strings pass
 *       through.</li>
 *   <li>Catalog {@code format: date} values ({@code 2015-04-19}) become midnight in the account zone and
 *       {@code format: date-time} values ({@code 2015-01-23T04:10:20Z}) the same instant in that zone, both
 *       written {@code uuuu-MM-dd'T'HH:mm:ss±hh:mm} ({@code 2015-04-19T00:00:00-07:00},
 *       {@code 2015-01-22T20:10:20-08:00}).</li>
 *   <li>An {@code {id, refName}} object is the opportunity {@code status} text ({@code Issued Quote}), an
 *       enumeration written as its SuiteTalk constant name ({@code CUSTOMER}, {@code AUTO}), or a reference
 *       {@code {externalId: null, type: null, internalId, name}}.</li>
 *   <li>Item supply plans keep only {@code internalId} and the references {@code item}, {@code location},
 *       {@code units} (only when set) and {@code subsidiary}; their {@code order} lines are omitted.</li>
 *   <li>Opportunity addresses become the fixed 15-key SuiteTalk {@code Address} with nulls kept, the country
 *       code translated through {@code netsuite/country-enum.properties} ({@code US} → {@code UNITED_STATES}).</li>
 *   <li>Root {@code custentity*}/{@code custbody*} fields become one {@code customFieldList.customField} element
 *       keyed {@code <Type>CustomFieldRef__<scriptId>}: scalars as text ({@code "false"}, {@code "50"}), selects as
 *       {@code {internalId, name, externalId: null, typeId}} with {@code typeId} taken only from
 *       {@code sourceIds}. A select without a resolved source fails with {@link IllegalStateException} naming the
 *       script ID; {@code typeId} is never null or guessed.</li>
 * </ul>
 *
 * <h2>Key order</h2>
 * The committed key orders are {@code java.util.HashMap} iteration orders: inserting each committed object's keys in
 * their committed order into a new {@code HashMap} iterates them in that order, while any other insertion order
 * tried does not. Every output object except a reference is therefore a default-constructed {@code HashMap}, never
 * pre-sized, copy-constructed or shrunk by a removal, because the bucket count and the insertion order inside a
 * colliding bucket ({@code consolDepositBalance} before {@code aging3}, {@code addr1} before {@code city},
 * {@code state} before {@code override}) decide the order. Each object is built in two steps: its entries are
 * computed into a staging map, then put into a fresh {@code HashMap} in the canonical order copied from the
 * committed examples, followed by any other key in alphabetical order. A reference is the one
 * {@code LinkedHashMap}, because a {@code HashMap} would iterate it {@code internalId, name, externalId, type}.
 *
 * <h2>Plan defaults awaiting Tier 2B evidence</h2>
 * No committed example shows a {@code Double}, {@code String}, {@code Date} or {@code MultiSelect} custom field,
 * so their rendering ({@code Double.toString}, the body date text, the plain string, a list of select values) is
 * a plan default; so are the enumeration fallbacks beyond the named customer fields (a catalog {@code enum}
 * keyword, or an {@code id} that is not an integer literal) and the constant-name rule for multi-word values.
 *
 * <p>The instance is immutable and thread-safe; it reads the country table once and does no other I/O.
 */
@Component
public class NetsuiteRecordShapeMapper {

    /** The record types whose shape this class rebuilds. */
    public enum RecordKind {
        CUSTOMER,
        ITEM_SUPPLY_PLAN,
        OPPORTUNITY
    }

    /** SuiteTalk custom field reference types; {@code label} is the {@code <Type>} of {@code <Type>CustomFieldRef}. */
    private enum CustomType {
        BOOLEAN("Boolean"),
        LONG("Long"),
        DOUBLE("Double"),
        DATE("Date"),
        STRING("String"),
        SELECT("Select"),
        MULTI_SELECT("MultiSelect");

        private final String label;

        CustomType(String label) {
            this.label = label;
        }
    }

    private static final String COUNTRY_TABLE = "netsuite/country-enum.properties";

    private static final DateTimeFormatter OFFSET_DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.ROOT);

    /** Record internal IDs are integer literals; an {@code id} of any other form names an enumeration value. */
    private static final Pattern INTEGER_LITERAL = Pattern.compile("-?\\d+");

    private static final Pattern LEADING_UNDERSCORES = Pattern.compile("^_+");

    private static final Set<String> IGNORED_ROOT_FIELDS = Set.of("links", "refName");

    private static final Set<String> CUSTOMER_ENUM_FIELDS = Set.of("stage", "creditHoldOverride");

    private static final Set<String> OPPORTUNITY_ADDRESS_FIELDS = Set.of("billingAddress", "shippingAddress");

    private static final List<String> ITEM_SUPPLY_PLAN_REFERENCES = List.of("item", "location", "units", "subsidiary");

    private static final List<String> CUSTOMER_ORDER = List.of(
            "lastName", "isInactive", "companyName", "receivablesAccount", "consolBalance", "isPerson",
            "lastPageVisited", "consolAging2", "consolAging3", "internalId", "visits", "billPay", "consolAging4",
            "dateCreated", "aging2", "consolDepositBalance", "aging3", "accessRole", "altEmail", "consolAging1",
            "aging1", "unbilledOrders", "consolUnbilledOrders", "currency", "aging4", "consolOverdueBalance",
            "firstVisit", "email", "giveAccess", "customFieldList", "taxable", "lastModifiedDate", "lastVisit",
            "externalId", "entityId", "subsidiary", "clickStream", "shipComplete", "firstName", "stage",
            "creditHoldOverride", "phone", "consolAging", "entityStatus", "aging", "startDate", "defaultAddress");

    private static final List<String> OPPORTUNITY_ORDER = List.of(
            "tranId", "estGrossProfitPercent", "salesRep", "projectedTotal", "title", "expectedCloseDate",
            "internalId", "currencyName", "exchangeRate", "weightedTotal", "currency", "totalCostEstimate",
            "lastModifiedDate", "leadSource", "probability", "daysOpen", "forecastType", "subsidiary", "createdDate",
            "entityStatus", "estGrossProfit", "shippingAddress", "tranDate", "location", "billingAddress",
            "shipIsResidential", "isBudgetApproved", "entity", "status");

    private static final List<String> ITEM_SUPPLY_PLAN_ORDER =
            List.of("internalId", "item", "location", "units", "subsidiary");

    private static final List<String> CUSTOM_FIELD_ORDER = List.of(
            "BooleanCustomFieldRef__custentity_fmt_customer_credit_on_hold",
            "SelectCustomFieldRef__custentity8",
            "LongCustomFieldRef__custentity_cust_priority",
            "SelectCustomFieldRef__custentity7",
            "SelectCustomFieldRef__custentity_fmt_cur_sales_order",
            "BooleanCustomFieldRef__custentity_fmt_cust_credit_on_hold",
            "SelectCustomFieldRef__custentity6",
            "BooleanCustomFieldRef__custentity_2663_direct_debit",
            "BooleanCustomFieldRef__custentity18",
            "BooleanCustomFieldRef__custentity_2663_customer_refund");

    private final ZoneId zone;

    private final Map<String, String> countries;

    /**
     * Creates the mapper Spring injects, rendering dates in {@code netsuite.time-zone}.
     *
     * @param properties the NetSuite settings; only {@link NetsuiteProperties#getTimeZone()} is read
     */
    @Autowired
    public NetsuiteRecordShapeMapper(NetsuiteProperties properties) {
        this(properties.getTimeZone());
    }

    /**
     * Creates a mapper rendering dates and date-times in the given account time zone.
     *
     * @param zone the NetSuite account time zone, for example {@code America/Los_Angeles}
     * @throws IllegalStateException when {@code netsuite/country-enum.properties} is missing or unreadable
     */
    public NetsuiteRecordShapeMapper(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.countries = loadCountries(getClass().getClassLoader());
    }

    /**
     * Lists the custom select fields whose values need a {@code typeId}: the root {@code custentity*} and
     * {@code custbody*} fields that hold a value in at least one record and whose custom type is {@code Select} or
     * {@code MultiSelect}. The caller resolves them with {@code NetsuiteRestClient.customFieldSourceIds} and hands
     * the result to {@link #toSuiteTalk} as {@code sourceIds}.
     *
     * @param records the REST records about to be mapped; {@code null} reads as empty
     * @param catalog the records' metadata catalog; {@code null} reads as empty
     * @return the script IDs in sorted order; empty when there are none
     */
    public Set<String> selectCustomFieldScriptIds(List<Map<String, Object>> records, Map<String, Object> catalog) {
        Set<String> scriptIds = new TreeSet<>();
        if (records == null) {
            return scriptIds;
        }
        Map<String, Object> properties = asMap(asMap(catalog).get("properties"));
        for (Map<String, Object> record : records) {
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();
                if (value != null && isCustom(field)) {
                    CustomType type = customType(asMap(properties.get(field)), value);
                    if (type == CustomType.SELECT || type == CustomType.MULTI_SELECT) {
                        scriptIds.add(field);
                    }
                }
            }
        }
        return scriptIds;
    }

    /**
     * Rebuilds the SuiteTalk record of one REST record.
     *
     * @param kind       the record type
     * @param restRecord the REST record; it is not modified
     * @param catalog    the record type's metadata catalog; not read for item supply plans; {@code null} reads as
     *                   empty
     * @param sourceIds  custom select script ID → select source ID; {@code null} reads as empty
     * @return the SuiteTalk-shaped record, a {@code HashMap} that iterates in the committed key order
     * @throws IllegalStateException when a custom select field has no resolved source ID (the message names the
     *         script ID), or an address country code is missing from the country table
     */
    public Map<String, Object> toSuiteTalk(RecordKind kind, Map<String, Object> restRecord,
                                           Map<String, Object> catalog, Map<String, String> sourceIds) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(restRecord, "restRecord");
        if (kind == RecordKind.ITEM_SUPPLY_PLAN) {
            return itemSupplyPlan(restRecord);
        }
        return bodyRecord(kind, restRecord, asMap(asMap(catalog).get("properties")),
                sourceIds == null ? Map.of() : sourceIds);
    }

    /** An item supply plan: its internal ID and four whitelisted references, without the {@code order} lines. */
    private static Map<String, Object> itemSupplyPlan(Map<String, Object> restRecord) {
        Map<String, Object> staged = new LinkedHashMap<>();
        Object id = restRecord.get("id");
        if (id != null) {
            staged.put("internalId", String.valueOf(id));
        }
        for (String field : ITEM_SUPPLY_PLAN_REFERENCES) {
            Object value = restRecord.get(field);
            if (value != null) {
                staged.put(field, reference(value));
            }
        }
        return ordered(staged, ITEM_SUPPLY_PLAN_ORDER);
    }

    /** A customer or opportunity: catalog-declared body fields, the addresses and the custom field list. */
    private Map<String, Object> bodyRecord(RecordKind kind, Map<String, Object> restRecord,
                                           Map<String, Object> properties, Map<String, String> sourceIds) {
        Map<String, Object> staged = new LinkedHashMap<>();
        Map<String, Object> customFields = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : restRecord.entrySet()) {
            String field = entry.getKey();
            Object value = entry.getValue();
            if (value == null || IGNORED_ROOT_FIELDS.contains(field)) {
                continue;
            }
            Map<String, Object> schema = asMap(properties.get(field));
            // internalId and the two opportunity addresses have shape rules of their own, so they are written
            // whether or not the catalog declares them; every other body field needs the catalog entry.
            if (isCustom(field)) {
                CustomType type = customType(schema, value);
                customFields.put(type.label + "CustomFieldRef__" + field,
                        customValue(field, type, value, schema, sourceIds));
            } else if ("id".equals(field)) {
                staged.put("internalId", String.valueOf(value));
            } else if (kind == RecordKind.OPPORTUNITY && OPPORTUNITY_ADDRESS_FIELDS.contains(field)) {
                if (value instanceof Map<?, ?> address) {
                    staged.put(field, address(address));
                }
            } else if (properties.containsKey(field)) {
                Object converted = bodyValue(kind, field, value, schema);
                if (converted != null) {
                    staged.put(field, converted);
                }
            }
        }
        if (!customFields.isEmpty()) {
            Map<String, Object> customFieldList = new HashMap<>();
            customFieldList.put("customField", new ArrayList<>(List.of(ordered(customFields, CUSTOM_FIELD_ORDER))));
            staged.put("customFieldList", customFieldList);
        }
        return ordered(staged, kind == RecordKind.CUSTOMER ? CUSTOMER_ORDER : OPPORTUNITY_ORDER);
    }

    /**
     * Converts a body field value; {@code null} means the field produces no key (a sublist, a subrecord, or an
     * object without text).
     */
    private Object bodyValue(RecordKind kind, String field, Object value, Map<String, Object> schema) {
        if (value instanceof Map<?, ?> object) {
            return objectValue(kind, field, object, schema);
        }
        if (value instanceof Number number) {
            return number(number);
        }
        if (value instanceof String text && isDateFormat(schema)) {
            return dateText(text, schema);
        }
        return value instanceof List<?> ? null : value;
    }

    /** Classifies an {@code {id, refName}} body object as the opportunity status, an enumeration or a reference. */
    private static Object objectValue(RecordKind kind, String field, Map<?, ?> object, Map<String, Object> schema) {
        if (kind == RecordKind.OPPORTUNITY && "status".equals(field)) {
            return object.get("refName");
        }
        Object id = object.get("id");
        if (id == null || object.containsKey("items")) {
            return null;
        }
        String text = String.valueOf(id);
        boolean enumeration = (kind == RecordKind.CUSTOMER && CUSTOMER_ENUM_FIELDS.contains(field))
                || schema.containsKey("enum")
                || asMap(asMap(schema.get("properties")).get("id")).containsKey("enum")
                || !INTEGER_LITERAL.matcher(text).matches();
        return enumeration ? constantName(text) : reference(object);
    }

    /**
     * A SuiteTalk {@code RecordRef}. It is the one {@code LinkedHashMap} of the output: the committed
     * {@code externalId, type, internalId, name} order is not a {@code HashMap} order.
     */
    private static Map<String, Object> reference(Object value) {
        Map<?, ?> object = value instanceof Map<?, ?> map ? map : Map.of("id", value);
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("externalId", null);
        reference.put("type", null);
        reference.put("internalId", Objects.toString(object.get("id"), null));
        reference.put("name", object.get("refName"));
        return reference;
    }

    /**
     * The SuiteTalk enumeration constant of a REST enumeration value, named the way JAXB names enum constants:
     * leading {@code _} removed, a {@code _} inserted before an upper-case letter that follows a lower-case letter
     * or digit, everything upper-cased ({@code _customer} → {@code CUSTOMER}, {@code onHold} → {@code ON_HOLD},
     * {@code AUTO} unchanged).
     */
    private static String constantName(String value) {
        String text = LEADING_UNDERSCORES.matcher(value).replaceFirst("");
        StringBuilder name = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char previous = text.charAt(i - 1);
                if (Character.isLowerCase(previous) || Character.isDigit(previous)) {
                    name.append('_');
                }
            }
            name.append(c);
        }
        return name.toString().toUpperCase(Locale.ROOT);
    }


    /**
     * A SuiteTalk {@code Address}: the 15 keys put in their committed order, absent values kept as {@code null}.
     * {@code addrText} passes through unchanged, {@code <br>} and line breaks included.
     */
    private Map<String, Object> address(Map<?, ?> rest) {
        Map<String, Object> address = new HashMap<>();
        address.put("zip", rest.get("zip"));
        address.put("country", country(rest.get("country")));
        address.put("addr2", rest.get("addr2"));
        address.put("addr1", rest.get("addr1"));
        address.put("city", rest.get("city"));
        address.put("addr3", rest.get("addr3"));
        address.put("addrText", rest.get("addrText"));
        address.put("addrPhone", rest.get("addrPhone"));
        address.put("internalId", Objects.toString(rest.get("id"), null));
        address.put("addressee", rest.get("addressee"));
        address.put("attention", rest.get("attention"));
        address.put("state", rest.get("state"));
        address.put("override", rest.get("override"));
        address.put("nullFieldList", null);
        address.put("customFieldList", null);
        return address;
    }

    /** The SuiteTalk {@code Country} constant of a REST country, given as its code or as {@code {id, refName}}. */
    private String country(Object value) {
        Object code = value instanceof Map<?, ?> map ? map.get("id") : value;
        if (code == null) {
            return null;
        }
        String constant = countries.get(String.valueOf(code));
        if (constant == null) {
            throw new IllegalStateException("Unmapped NetSuite country code: " + code);
        }
        return constant;
    }

    private static boolean isCustom(String field) {
        return field.startsWith("custentity") || field.startsWith("custbody");
    }

    /** The custom field type, from the catalog field schema, or from the value when the catalog lacks the field. */
    private static CustomType customType(Map<String, Object> schema, Object value) {
        return switch (String.valueOf(schema.get("type"))) {
            case "array" -> CustomType.MULTI_SELECT;
            case "object" -> asMap(schema.get("properties")).containsKey("items")
                    ? CustomType.MULTI_SELECT
                    : CustomType.SELECT;
            case "boolean" -> CustomType.BOOLEAN;
            case "integer" -> CustomType.LONG;
            case "number" -> CustomType.DOUBLE;
            case "string" -> isDateFormat(schema) ? CustomType.DATE : CustomType.STRING;
            default -> schema.containsKey("$ref") ? CustomType.SELECT : typeOfValue(value);
        };
    }

    private static CustomType typeOfValue(Object value) {
        if (value instanceof Boolean) {
            return CustomType.BOOLEAN;
        }
        if (value instanceof Number number) {
            return number(number) instanceof BigInteger ? CustomType.LONG : CustomType.DOUBLE;
        }
        if (value instanceof List<?> || asMap(value).containsKey("items")) {
            return CustomType.MULTI_SELECT;
        }
        return value instanceof Map<?, ?> ? CustomType.SELECT : CustomType.STRING;
    }

    /** The value of a {@code <Type>CustomFieldRef}: scalars as text, selects as SuiteTalk select values. */
    private Object customValue(String scriptId, CustomType type, Object value, Map<String, Object> schema,
                               Map<String, String> sourceIds) {
        return switch (type) {
            case BOOLEAN, STRING -> String.valueOf(value);
            case LONG -> value instanceof Number number ? numberText(number) : String.valueOf(value);
            case DOUBLE -> value instanceof Number number
                    ? Double.toString(number.doubleValue())
                    : String.valueOf(value);
            case DATE -> dateText(String.valueOf(value), schema);
            case SELECT -> selectValue(value, typeId(scriptId, sourceIds));
            case MULTI_SELECT -> multiSelectValue(value, typeId(scriptId, sourceIds));
        };
    }

    /**
     * The select source of a custom field, written verbatim ({@code -30}, a standard transaction source, included).
     * The exact script ID is tried first, then its lower-case form, because
     * {@code NetsuiteRestClient.customFieldSourceIds} reports lower-cased script IDs.
     */
    private static String typeId(String scriptId, Map<String, String> sourceIds) {
        String typeId = sourceIds.getOrDefault(scriptId, sourceIds.get(scriptId.toLowerCase(Locale.ROOT)));
        if (typeId == null || typeId.isBlank()) {
            throw new IllegalStateException(
                    "No select source (typeId) resolved for NetSuite custom field " + scriptId);
        }
        return typeId;
    }

    private static List<Object> multiSelectValue(Object value, String typeId) {
        List<?> items = value instanceof List<?> list ? list : asList(asMap(value).get("items"));
        List<Object> selects = new ArrayList<>();
        for (Object item : items) {
            selects.add(selectValue(item, typeId));
        }
        return selects;
    }

    /** A SuiteTalk {@code ListOrRecordRef} select value, keys put in the committed order. */
    private static Map<String, Object> selectValue(Object value, String typeId) {
        Map<?, ?> object = value instanceof Map<?, ?> map ? map : Map.of("id", value);
        Map<String, Object> select = new HashMap<>();
        select.put("internalId", Objects.toString(object.get("id"), null));
        select.put("name", object.get("refName"));
        select.put("externalId", null);
        select.put("typeId", typeId);
        return select;
    }

    /** A number without trailing zeros: {@code BigInteger} when integral, otherwise {@code BigDecimal}. */
    private static Object number(Number value) {
        BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
        return decimal.scale() <= 0 ? decimal.toBigIntegerExact() : decimal;
    }

    private static String numberText(Number value) {
        Object number = number(value);
        return number instanceof BigDecimal decimal ? decimal.toPlainString() : number.toString();
    }

    private static boolean isDateFormat(Map<String, Object> schema) {
        Object format = schema.get("format");
        return "date".equals(format) || "date-time".equals(format);
    }

    /**
     * A date (midnight in the account zone) or date-time (the same instant in the account zone, to the second) as
     * SuiteTalk wrote it; a {@code format: date} value carrying a time is read as a date-time.
     */
    private String dateText(String value, Map<String, Object> schema) {
        ZonedDateTime time = "date".equals(schema.get("format")) && value.indexOf('T') < 0
                ? LocalDate.parse(value).atStartOfDay(zone)
                : OffsetDateTime.parse(value).atZoneSameInstant(zone).truncatedTo(ChronoUnit.SECONDS);
        return OFFSET_DATE_TIME.format(time);
    }

    /**
     * Puts the staged entries into a fresh default-sized {@code HashMap}: the canonical keys that are present in
     * canonical order, then every other key in alphabetical order. Only insertion happens on the result, so its
     * bucket count and the order inside colliding buckets are those of the committed examples.
     */
    private static Map<String, Object> ordered(Map<String, Object> staged, List<String> canonicalOrder) {
        Map<String, Object> result = new HashMap<>();
        for (String key : canonicalOrder) {
            if (staged.containsKey(key)) {
                result.put(key, staged.get(key));
            }
        }
        Map<String, Object> remainder = new TreeMap<>(staged);
        remainder.keySet().removeAll(canonicalOrder);
        remainder.forEach(result::put);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, String> loadCountries(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(COUNTRY_TABLE)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + COUNTRY_TABLE + " is missing");
            }
            Properties table = new Properties();
            table.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> codes = new HashMap<>();
            for (String code : table.stringPropertyNames()) {
                codes.put(code, table.getProperty(code));
            }
            return Map.copyOf(codes);
        } catch (IOException e) {
            throw new IllegalStateException("Classpath resource " + COUNTRY_TABLE + " cannot be read", e);
        }
    }
}

