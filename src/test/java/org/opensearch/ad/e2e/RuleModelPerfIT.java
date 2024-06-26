/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ad.e2e;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.opensearch.client.RestClient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RuleModelPerfIT extends AbstractRuleTestCase {
    static final Logger LOG = (Logger) LogManager.getLogger(RuleModelPerfIT.class);

    public void testRule() throws Exception {
        // TODO: this test case will run for a much longer time and timeout with security enabled
        if (!isHttps()) {
            disableResourceNotFoundFaultTolerence();
            // there are 8 entities in the data set. Each one needs 1500 rows as training data.
            Map<String, Double> minPrecision = new HashMap<>();
            minPrecision.put("Phoenix", 0.5);
            minPrecision.put("Scottsdale", 0.5);
            Map<String, Double> minRecall = new HashMap<>();
            minRecall.put("Phoenix", 0.9);
            minRecall.put("Scottsdale", 0.6);
            verifyRule("rule", 10, minPrecision.size(), 1500, minPrecision, minRecall, 20);
        }
    }

    private void verifyTestResults(
        Triple<Map<String, double[]>, Integer, Map<String, Set<Integer>>> testResults,
        Map<String, List<Entry<Instant, Instant>>> anomalies,
        Map<String, Double> minPrecision,
        Map<String, Double> minRecall,
        int maxError
    ) {
        Map<String, double[]> resultMap = testResults.getLeft();
        Map<String, Set<Integer>> foundWindows = testResults.getRight();

        for (Entry<String, double[]> entry : resultMap.entrySet()) {
            String entity = entry.getKey();
            double[] testResultsArray = entry.getValue();
            double positives = testResultsArray[0];
            double truePositives = testResultsArray[1];

            // precision = predicted anomaly points that are true / predicted anomaly points
            double precision = positives > 0 ? truePositives / positives : 0;
            double minPrecisionValue = minPrecision.getOrDefault(entity, .4);
            assertTrue(
                String
                    .format(
                        Locale.ROOT,
                        "precision expected at least %f but got %f. positives %f, truePositives %f",
                        minPrecisionValue,
                        precision,
                        positives,
                        truePositives
                    ),
                precision >= minPrecisionValue
            );

            // recall = windows containing predicted anomaly points / total anomaly windows
            int anomalyWindow = anomalies.getOrDefault(entity, new ArrayList<>()).size();
            int foundWindowSize = foundWindows.getOrDefault(entity, new HashSet<>()).size();
            double recall = anomalyWindow > 0 ? foundWindowSize * 1.0d / anomalyWindow : 0;
            double minRecallValue = minRecall.getOrDefault(entity, .7);
            assertTrue(
                String
                    .format(
                        Locale.ROOT,
                        "recall should be at least %f but got %f. anomalyWindow %d, foundWindowSize %d ",
                        minRecallValue,
                        recall,
                        anomalyWindow,
                        foundWindowSize
                    ),
                recall >= minRecallValue
            );

            LOG.info("Entity {}, Precision: {}, Window recall: {}", entity, precision, recall);
        }

        int errors = testResults.getMiddle();
        assertTrue(errors <= maxError);
    }

    public void verifyRule(
        String datasetName,
        int intervalMinutes,
        int numberOfEntities,
        int trainTestSplit,
        Map<String, Double> minPrecision,
        Map<String, Double> minRecall,
        int maxError
    ) throws Exception {
        verifyRule(datasetName, intervalMinutes, numberOfEntities, trainTestSplit, minPrecision, minRecall, maxError, false);
    }

    public void verifyRule(
        String datasetName,
        int intervalMinutes,
        int numberOfEntities,
        int trainTestSplit,
        Map<String, Double> minPrecision,
        Map<String, Double> minRecall,
        int maxError,
        boolean useDateNanos
    ) throws Exception {

        String labelFileName = String.format(Locale.ROOT, "data/%s.label", datasetName);
        Map<String, List<Entry<Instant, Instant>>> anomalies = getAnomalyWindowsMap(labelFileName);

        TrainResult trainResult = ingestTrainData(datasetName, intervalMinutes, numberOfEntities, trainTestSplit, useDateNanos);

        Triple<Map<String, double[]>, Integer, Map<String, Set<Integer>>> results = getTestResults(
            trainResult.detectorId,
            trainResult.data,
            trainResult.rawDataTrainTestSplit,
            intervalMinutes,
            anomalies,
            client(),
            numberOfEntities,
            trainResult.windowDelay
        );
        verifyTestResults(results, anomalies, minPrecision, minRecall, maxError);
    }

    private Triple<Map<String, double[]>, Integer, Map<String, Set<Integer>>> getTestResults(
        String detectorId,
        List<JsonObject> data,
        int rawTrainTestSplit,
        int intervalMinutes,
        Map<String, List<Entry<Instant, Instant>>> anomalies,
        RestClient client,
        int numberOfEntities,
        Duration windowDelay
    ) throws Exception {

        Map<String, double[]> res = new HashMap<>();
        int errors = 0;
        // an entity might have missing values (e.g., at timestamp 1694713200000).
        // Use a map to record the number of times we have seen them.
        // data start time -> the number of entities
        TreeMap<String, Integer> entityMap = new TreeMap<>();
        for (int i = rawTrainTestSplit; i < data.size(); i++) {
            if (scoreOneResult(data.get(i), entityMap, windowDelay, intervalMinutes, detectorId, client, numberOfEntities)) {
                errors++;
            }
        }

        // hash set to dedup
        Map<String, Set<Integer>> foundWindow = new HashMap<>();

        // Iterate over the TreeMap in ascending order of keys
        for (Map.Entry<String, Integer> entry : entityMap.entrySet()) {
            String beginTimeStampAsString = entry.getKey();
            int entitySize = entry.getValue();
            Instant begin = Instant.ofEpochMilli(Long.parseLong(beginTimeStampAsString));
            Instant end = begin.plus(intervalMinutes, ChronoUnit.MINUTES);
            try {
                List<JsonObject> sourceList = getAnomalyResult(detectorId, end, entitySize, client);

                assertTrue(
                    String
                        .format(
                            Locale.ROOT,
                            "the number of results is %d at %s, expected %d ",
                            sourceList.size(),
                            beginTimeStampAsString,
                            entitySize
                        ),
                    sourceList.size() == entitySize
                );
                for (int j = 0; j < entitySize; j++) {
                    JsonObject source = sourceList.get(j);
                    double anomalyGrade = getAnomalyGrade(source);
                    assertTrue("anomalyGrade cannot be negative", anomalyGrade >= 0);
                    if (anomalyGrade > 0) {
                        String entity = getEntity(source);
                        double[] entityResult = res.computeIfAbsent(entity, key -> new double[] { 0, 0 });
                        // positive++
                        entityResult[0]++;
                        Instant anomalyTime = getAnomalyTime(source, begin);
                        LOG.info("Found anomaly: entity {}, time {} result {}.", entity, anomalyTime, source);
                        int anomalyWindow = isAnomaly(anomalyTime, anomalies.getOrDefault(entity, new ArrayList<>()));
                        if (anomalyWindow != -1) {
                            LOG.info("True anomaly: entity {}, time {}.", entity, begin);
                            // truePositives++;
                            entityResult[1]++;
                            Set<Integer> window = foundWindow.computeIfAbsent(entity, key -> new HashSet<>());
                            window.add(anomalyWindow);
                        }
                    }
                }
            } catch (Exception e) {
                errors++;
                LOG.error("failed to get detection results", e);
            }
        }
        return Triple.of(res, errors, foundWindow);
    }

    public Map<String, List<Entry<Instant, Instant>>> getAnomalyWindowsMap(String labelFileName) throws Exception {
        JsonObject jsonObject = JsonParser
            .parseReader(new FileReader(new File(getClass().getResource(labelFileName).toURI()), Charset.defaultCharset()))
            .getAsJsonObject();

        Map<String, List<Entry<Instant, Instant>>> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            List<Entry<Instant, Instant>> anomalies = new ArrayList<>();
            JsonElement value = entry.getValue();
            if (value.isJsonArray()) {
                for (JsonElement elem : value.getAsJsonArray()) {
                    JsonElement beginElement = elem.getAsJsonArray().get(0);
                    JsonElement endElement = elem.getAsJsonArray().get(1);
                    Instant begin = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(beginElement.getAsString()));
                    Instant end = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(endElement.getAsString()));
                    anomalies.add(new SimpleEntry<>(begin, end));
                }
            }
            map.put(entry.getKey(), anomalies);
        }
        return map;
    }
}
