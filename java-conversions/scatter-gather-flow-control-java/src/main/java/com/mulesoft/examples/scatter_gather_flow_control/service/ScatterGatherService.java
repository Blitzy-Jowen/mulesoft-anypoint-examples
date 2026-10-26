/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.scatter_gather_flow_control.client.AggregationReportMailer;
import com.mulesoft.examples.scatter_gather_flow_control.mapper.ContactsCsvMapper;
import com.mulesoft.examples.scatter_gather_flow_control.model.BranchResult;

/**
 * Reproduces flow {@code scatter-gatherFlow} of {@code scatter-gather-flow-control/src/main/app/scatter-gather.xml}
 * (lines 6-60), the processing behind the {@code /scatterGather} listener:
 * <ol>
 *   <li>{@code scatter-gather} (lines 8-44): the routes {@code sourceA} and {@code sourceB} run concurrently and
 *       their results are merged by {@link ContactMergeAggregationStrategy};</li>
 *   <li>the DataWeave CSV script and {@code object-to-string-transformer} (lines 45-57):
 *       {@link ContactsCsvMapper};</li>
 *   <li>{@code flow-ref outboundFlow} and {@code set-payload #[payload]} (lines 58-59):
 *       {@link AggregationReportMailer}, whose result is the flow's response payload.</li>
 * </ol>
 *
 * <p>The original declares no {@code timeout} on the scatter-gather, no logger and no exception strategy, so the
 * routes are awaited without a time limit and every failure propagates to the caller unchanged.
 *
 * <p>The routes run on Spring Boot's auto-configured {@code applicationTaskExecutor}. That bean backs off when any
 * other {@link Executor} bean exists, so the project must declare none, or this qualifier finds no target.
 */
@Service
public class ScatterGatherService {

    private final ContactMergeAggregationStrategy strategy;

    private final ContactsCsvMapper contactsCsvMapper;

    private final AggregationReportMailer aggregationReportMailer;

    private final Executor executor;

    /**
     * Creates the service.
     *
     * @param strategy the ported custom aggregation strategy of the scatter-gather
     * @param contactsCsvMapper the owner of the CSV DataWeave script
     * @param aggregationReportMailer the replacement of {@code outboundFlow}
     * @param executor the executor the two routes run on concurrently
     */
    public ScatterGatherService(ContactMergeAggregationStrategy strategy,
                                ContactsCsvMapper contactsCsvMapper,
                                AggregationReportMailer aggregationReportMailer,
                                @Qualifier("applicationTaskExecutor") Executor executor) {
        this.strategy = strategy;
        this.contactsCsvMapper = contactsCsvMapper;
        this.aggregationReportMailer = aggregationReportMailer;
        this.executor = executor;
    }

    /**
     * Route {@code sourceA}, the {@code expression-component} of scatter-gather.xml lines 10-26.
     *
     * <p>Every call builds new mutable collections of the types the MEL script created.
     *
     * @return a new {@link ArrayList} of two new {@link HashMap}s: vlado (Id 1) and michal (Id 2)
     */
    public List<Map<String, String>> sourceA() {
        List<Map<String, String>> sourceA = new ArrayList<>();

        Map<String, String> firstContact = new HashMap<>();
        firstContact.put("Id", "1");
        firstContact.put("Name", "vlado");
        firstContact.put("Email", "vlado@email.com");

        Map<String, String> secondContact = new HashMap<>();
        secondContact.put("Id", "2");
        secondContact.put("Name", "michal");
        secondContact.put("Email", "michal@email.com");

        sourceA.add(firstContact);
        sourceA.add(secondContact);

        return sourceA;
    }

    /**
     * Route {@code sourceB}, the {@code expression-component} of scatter-gather.xml lines 27-43.
     *
     * <p>Every call builds new mutable collections of the types the MEL script created.
     *
     * @return a new {@link ArrayList} of two new {@link HashMap}s: peter (Id 1) and vlado (Id 2)
     */
    public List<Map<String, String>> sourceB() {
        List<Map<String, String>> sourceB = new ArrayList<>();

        Map<String, String> firstContact = new HashMap<>();
        firstContact.put("Id", "1");
        firstContact.put("Name", "peter");
        firstContact.put("Email", "peter@email.com");

        Map<String, String> secondContact = new HashMap<>();
        secondContact.put("Id", "2");
        secondContact.put("Name", "vlado");
        secondContact.put("Email", "vlado@email.com");

        sourceB.add(firstContact);
        sourceB.add(secondContact);

        return sourceB;
    }

    /**
     * Runs both routes concurrently and waits for both, as the scatter-gather router does.
     *
     * <p>A failing route does not fail this method: its exception is recorded in its {@link BranchResult} and the
     * aggregation strategy decides the outcome, as Mule's {@code AggregationContext} did.
     *
     * @return one result per route in route order, route A first and route B second
     */
    public List<BranchResult> scatter() {
        // The routes are invoked through method references on this, so that when the bean is a Mockito or Spring
        // @SpyBean, this is the spied instance and stubs of sourceA() and sourceB() apply.
        CompletableFuture<List<Map<String, String>>> a = CompletableFuture.supplyAsync(this::sourceA, executor);
        CompletableFuture<List<Map<String, String>>> b = CompletableFuture.supplyAsync(this::sourceB, executor);

        // No time limit: the original scatter-gather declares no timeout. A route's failure is read per route below.
        CompletableFuture.allOf(a, b).exceptionally(t -> null).join();

        return List.of(toResult(a), toResult(b));
    }

    /**
     * Runs the flow: scatter-gather with the custom aggregation, CSV rendering, then the report mail.
     *
     * @return the payload the flow returns to the HTTP caller, {@link AggregationReportMailer#BODY_TEXT}
     * @throws IllegalStateException when a route fails, from the aggregation strategy
     * @throws org.springframework.mail.MailException when the report mail cannot be built or sent
     */
    public String process() {
        // The aggregation-failure branch is unreachable with the fixed routes; only a test spy that makes a route
        // throw reaches it. Nothing is caught here: the original flow has no exception strategy.
        List<Map<String, String>> merged = strategy.aggregate(scatter());
        String csv = contactsCsvMapper.toCsv(merged);
        return aggregationReportMailer.send(csv);
    }

    private static BranchResult toResult(CompletableFuture<?> future) {
        try {
            return BranchResult.success(future.join());
        } catch (CompletionException e) {
            return BranchResult.failure(unwrap(e));
        } catch (CancellationException e) {
            return BranchResult.failure(e);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
