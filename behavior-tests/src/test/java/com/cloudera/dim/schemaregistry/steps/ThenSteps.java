/**
 * Copyright 2016-2021 Cloudera, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.cloudera.dim.schemaregistry.steps;

import com.cloudera.dim.schemaregistry.GlobalState;
import com.cloudera.dim.schemaregistry.TestAtlasServer;
import com.cloudera.dim.schemaregistry.TestSchemaRegistryServer;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hortonworks.registries.common.CollectionResponse;
import com.hortonworks.registries.schemaregistry.AggregatedSchemaBranch;
import com.hortonworks.registries.schemaregistry.AggregatedSchemaMetadataInfo;
import com.hortonworks.registries.schemaregistry.SchemaVersionInfo;
import com.hortonworks.registries.schemaregistry.errors.SchemaNotFoundException;
import com.hortonworks.registries.schemaregistry.exportimport.UploadResult;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Then;
import org.apache.commons.collections4.IterableUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.cloudera.dim.schemaregistry.GlobalState.AGGREGATED_SCHEMAS;
import static com.cloudera.dim.schemaregistry.GlobalState.COMPATIBILITY;
import static com.cloudera.dim.schemaregistry.GlobalState.HTTP_RESPONSE_CODE;
import static com.cloudera.dim.schemaregistry.GlobalState.SCHEMA_ID;
import static com.cloudera.dim.schemaregistry.GlobalState.SCHEMA_META_INFO;
import static com.cloudera.dim.schemaregistry.GlobalState.SCHEMA_VERSION_ID;
import static com.cloudera.dim.schemaregistry.GlobalState.SCHEMA_VERSION_TEXT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.http.protocol.HttpCoreContext.HTTP_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ThenSteps extends AbstractSteps {

    private static final Logger LOG = LoggerFactory.getLogger(ThenSteps.class);

    private final ExecutorService threadPool = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setDaemon(true).setNameFormat("then-%d").build());

    public ThenSteps(TestSchemaRegistryServer testServer, GlobalState sow, TestAtlasServer testAtlasServer) {
        super(testServer, sow, testAtlasServer);
    }

    @Then("the schema is successfully created")
    public void theSchemaIsSuccessfullyCreated() {
        assertNotNull(sow.getValue(SCHEMA_ID), "Schema meta was not created.");
    }

    @Then("the version is successfully created")
    public void theVersionIsSuccessfullyCreated() {
        assertNotNull(sow.getValue(SCHEMA_VERSION_ID), "Schema version was not created.");
    }

    @Then("the schema is successfully updated")
    public void theSchemaIsSuccessfullyUpdated() {
        assertNotNull(sow.getValue(SCHEMA_META_INFO), "Schema meta was not updated.");
    }

    @SuppressWarnings("unchecked")
    @Then("the resulting list size is {int}")
    public void theResultingListSizeIs(int size) {
        List<AggregatedSchemaMetadataInfo> aggregatedSchemas = (List<AggregatedSchemaMetadataInfo>) sow.getValue(AGGREGATED_SCHEMAS);
        assertNotNull(aggregatedSchemas, "List was null");
        assertEquals(size, aggregatedSchemas.size());
    }

    @SuppressWarnings("unchecked")
    @Then("the resulting list will contain the following {int} versions:")
    public void theResultingListWillContainTheFollowingVersions(int count, DataTable table) {
        List<AggregatedSchemaMetadataInfo> aggregatedSchemas = (List<AggregatedSchemaMetadataInfo>) sow.getValue(AGGREGATED_SCHEMAS);
        assertNotNull(aggregatedSchemas, "List was null");

        Collection<AggregatedSchemaBranch> schemaBranches = aggregatedSchemas.get(0).getSchemaBranches();
        assertNotNull(schemaBranches, "Schema has no branch");
        assertTrue(schemaBranches.size() > 0, "Schema list of branches is empty.");
        AggregatedSchemaBranch branch = IterableUtils.get(schemaBranches, 0);

        Collection<SchemaVersionInfo> versions = branch.getSchemaVersionInfos();
        assertNotNull(versions, "Schema has no versions");
        assertEquals(count, versions.size());

        Set<String> actualVersions = versions.stream().map(v -> v.getVersion().toString()).collect(Collectors.toSet());

        for (String row : table.asList()) {
            assertTrue(actualVersions.contains(row), "Actual Versions list does not contain: " + row);
        }
    }

    @Then("the response code is {int}")
    public void theResponseCodeIs(int code) {
        assertEquals(code, sow.getValue(HTTP_RESPONSE_CODE));
    }

    @Then("the response will contain {int} (entity)(entities)")
    public void theResponseWillContainSchemaVersions(int count) throws IOException {
        LOG.debug("Checking the response we received from schema registry");
        HttpResponse response = (HttpResponse) sow.getValue(HTTP_RESPONSE);
        assertNotNull(response, "No response found.");
        CollectionResponse result = objectMapper.readValue(response.getEntity().getContent(), CollectionResponse.class);
        assertEquals(count, result.getEntities().size());
    }

    @Then("the Avro test is compatible")
    public void theAvroTestIsCompatible() {
        assertNotNull(sow.getValue(COMPATIBILITY));
        assertTrue((Boolean) sow.getValue(COMPATIBILITY));
    }

    @Then("after waiting for no more than {int} seconds, a request is sent to Atlas to create a new schema {string}")
    public void afterWaitingARequestIsSentToAtlasToCreateANewSchemaAndBranch(int maxWait, String schemaName) throws Exception {
        pollSow(maxWait, sow -> testAtlasServer.verifyCreateMetaRequest(schemaName));
    }

    @Then("after waiting for no more than {int} seconds, a request is sent to Atlas to create a new version of schema {string}")
    public void afterWaitingARequestIsSentToAtlasToCreateANewVersion(int maxWait, String schemaName) throws Exception {
        pollSow(maxWait, sow -> testAtlasServer.verifyCreateVersionRequest(schemaName));
    }

    @Then("after waiting for no more than {int} seconds, a request is sent to Atlas to update schema {string}")
    public void afterWaitingARequestIsSentToAtlasToUpdateSchema(int maxWait, String schemaName) throws Exception {
        pollSow(maxWait, sow -> testAtlasServer.verifyUpdateMetaRequest(schemaName));
    }

    @Then("the model should be created in Atlas")
    public void theModelShouldBeCreatedInAtlas() throws Exception {
        pollSow(10, sow -> testAtlasServer.verifyCreateModelRequest());
        testAtlasServer.queryAtlasModel();
    }

    @Then("no request was made to Atlas to connect the {string} schema with any topic")
    public void noConnectionRequestMade(String topicName) {
        testAtlasServer.verifyNoConnectionWasMadeWith(topicName);
    }

    @Then("a new relationship was made in Atlas between the {string} schema and the {string} topic")
    public void aNewRelationshipWasMadeInAtlasBetweenTheSchemaAndTheTopic(String schemaName, String topicName) {
        testAtlasServer.verifySchemaAndTopicGotConnected(schemaName, topicName);
    }

    /**
     * Poll the StateOfTheWorld every 100ms. If the condition doesn't happen for maxTimeoutSec, an exception is thrown.
     */
    private void pollSow(int maxTimeoutSec, Predicate<GlobalState> condition) throws InterruptedException, ExecutionException, TimeoutException {
        Future<?> task = threadPool.submit(new Runnable() {
            @Override
            public synchronized void run() {
                while (!Thread.interrupted()) {
                    if (!condition.test(sow)) {
                        try {
                            wait(100);
                        } catch (InterruptedException iex) {
                            LOG.error("Interrupted while waiting for the response.", iex);
                            return;
                        }
                    } else {
                        break;
                    }
                }
            }
        });
        task.get(maxTimeoutSec, TimeUnit.SECONDS);
    }

    @And("no failed Atlas events remain")
    public void noFailedAtlasEventRemain() throws SQLException {
        assertEquals(0, testServer.countFailedAtlasEvents());
    }

    @Then("we should see the {string} schema with id {long} and version {int} on the {string} branch in the export with the same schema text")
    @SuppressWarnings("unchecked")
    public void weShouldSeeTheSchemaWithTheVersionOnTheBranchInTheExport(String name, Long id, Integer versionNo, String branchName) {
        List<AggregatedSchemaMetadataInfo> aggregatedSchemas = (List<AggregatedSchemaMetadataInfo>) sow.getValue(AGGREGATED_SCHEMAS);

        Optional<AggregatedSchemaMetadataInfo> aggregated = aggregatedSchemas.stream()
                .filter(s -> s.getSchemaMetadata().getName().equals(name)).findFirst();
        assertTrue(aggregated.isPresent());
        Optional<AggregatedSchemaBranch> branch = aggregated.get().getSchemaBranches().stream()
                .filter(b -> b.getSchemaBranch().getName().equalsIgnoreCase(branchName)).findFirst();
        assertTrue(branch.isPresent());
        Optional<SchemaVersionInfo> version = branch.get().getSchemaVersionInfos().stream()
                .filter(v ->
                        equalsIgnoringPrecision(v.getVersion(), versionNo) && equalsIgnoringPrecision(v.getId(), id)
                ).findFirst();
        assertTrue(version.isPresent());
        assertEquals(sow.getValue(SCHEMA_VERSION_TEXT), version.get().getSchemaText());
    }

    @Then("we should see the {string} schema with id {long} in the export")
    @SuppressWarnings("unchecked")
    public void weShouldSeeTheSchemaWithIdInTheExport(String name, Long id) {
        List<AggregatedSchemaMetadataInfo> aggregatedSchemas = (List<AggregatedSchemaMetadataInfo>) sow.getValue(AGGREGATED_SCHEMAS);

        Optional<AggregatedSchemaMetadataInfo> aggregated = aggregatedSchemas.stream()
            .filter(s -> s.getSchemaMetadata().getName().equals(name)).findFirst();
        assertTrue(aggregated.isPresent());
    }

    @Then("we should see {int} successfully imported versions in the response and {int} should have failed")
    public void inTheResponseWeShouldSeeSuccessfullyImportedVersionAndShouldHaveFailed(Integer successful, Integer failed) throws IOException {
        inTheResponseWeShouldSeeSuccessfullyImportedVersionAndShouldHaveFailedShowingTheIdAsFailing(successful, failed, null);
    }

    @Then("we should see {int} successfully imported versions in the response and {int} should have failed showing the id {long} as failing")
    public void inTheResponseWeShouldSeeSuccessfullyImportedVersionAndShouldHaveFailedShowingTheIdAsFailing(Integer successful, Integer failed, Long failedId) throws IOException {
        CloseableHttpResponse response = (CloseableHttpResponse) sow.getValue(HTTP_RESPONSE);
        String responseBody = EntityUtils.toString(response.getEntity(), UTF_8);
        UploadResult uploadResult = objectMapper.readValue(responseBody, UploadResult.class);
        LOG.info("uploadresult was: {}", uploadResult);
        assertEquals(successful, uploadResult.getSuccessCount());
        assertEquals(failed, uploadResult.getFailedCount());
        if (failedId != null) {
            assertTrue(uploadResult.getFailedIds().stream().anyMatch(id -> id.equals(failedId)));
        }
    }

    @Then("there should be a version {int} with id {long} of the schema {string} on the {string} branch")
    public void theThereShouldBeAVersionWithIdOfTheSchemaOnTheBranch(Integer version, Long id, String name, String branch) throws SchemaNotFoundException {
        theThereShouldBeAVersionWithIdOfTheSchemaOnTheBranchInTheRegistryWithCertainText(version, id, name, branch, null);
    }

    @Then("there should be a version {int} with id {long} of the schema {string} on the {string} branch in the Registry with the same schema text")
    public void theThereShouldBeAVersionWithIdOfTheSchemaOnTheBranchInTheRegistryWithTheSameSchemaText(Integer version, Long id, String name, String branch) throws SchemaNotFoundException {
        String schemaText = (String) sow.getValue(SCHEMA_VERSION_TEXT);
        theThereShouldBeAVersionWithIdOfTheSchemaOnTheBranchInTheRegistryWithCertainText(version, id, name, branch, schemaText);
    }

    public void theThereShouldBeAVersionWithIdOfTheSchemaOnTheBranchInTheRegistryWithCertainText(Integer version, Long id, String name, String branch, String schemaText) throws SchemaNotFoundException {
        Collection<SchemaVersionInfo> schemaVersionInfos = getSchemaRegistryClient().getAllVersions(branch, name);
        List<Long> versionIds = schemaVersionInfos.stream().map(SchemaVersionInfo::getId).collect(Collectors.toList());
        assertFalse(schemaVersionInfos.isEmpty(), "no version to schema " + name + " on branch " + branch);
        assertTrue(
                schemaVersionInfos.stream().anyMatch(svi -> equalsIgnoringPrecision(svi.getId(), id)),
                "no version with id " + id + " but only: " + versionIds
        );
        if (schemaText != null) {
            assertTrue(
                    schemaVersionInfos.stream().anyMatch(svi -> svi.getSchemaText().equals(schemaText)),
                    "no version with the same schema text"
            );
        }
    }

    private boolean equalsIgnoringPrecision(Number n1, Number n2) {
        return n1.toString().equals(n2.toString());
    }
}
