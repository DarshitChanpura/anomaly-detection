/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.ad.transport;

import static org.opensearch.ad.constant.ADCommonMessages.FAIL_TO_CREATE_DETECTOR;
import static org.opensearch.ad.constant.ADCommonMessages.FAIL_TO_UPDATE_DETECTOR;
import static org.opensearch.ad.settings.AnomalyDetectorSettings.AD_FILTER_BY_BACKEND_ROLES;
import static org.opensearch.timeseries.util.ParseUtils.*;
import static org.opensearch.timeseries.util.RestHandlerUtils.wrapRestActionListener;

import java.util.List;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.ad.indices.ADIndexManagement;
import org.opensearch.ad.model.AnomalyDetector;
import org.opensearch.ad.rest.handler.IndexAnomalyDetectorActionHandler;
import org.opensearch.ad.settings.AnomalyDetectorSettings;
import org.opensearch.ad.task.ADTaskManager;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.inject.Inject;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.rest.RestRequest;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.tasks.Task;
import org.opensearch.timeseries.feature.SearchFeatureDao;
import org.opensearch.timeseries.function.ExecutorFunction;
import org.opensearch.timeseries.util.ParseUtils;
import org.opensearch.timeseries.util.SecurityClientUtil;
import org.opensearch.transport.TransportService;

public class IndexAnomalyDetectorTransportAction extends HandledTransportAction<IndexAnomalyDetectorRequest, IndexAnomalyDetectorResponse> {
    private static final Logger LOG = LogManager.getLogger(IndexAnomalyDetectorTransportAction.class);
    private final Client client;
    private final SecurityClientUtil clientUtil;
    private final TransportService transportService;
    private final ADIndexManagement anomalyDetectionIndices;
    private final ClusterService clusterService;
    private final NamedXContentRegistry xContentRegistry;
    private final ADTaskManager adTaskManager;
    private volatile Boolean filterByEnabled;
    private final SearchFeatureDao searchFeatureDao;
    private final Settings settings;

    @Inject
    public IndexAnomalyDetectorTransportAction(
        TransportService transportService,
        ActionFilters actionFilters,
        Client client,
        SecurityClientUtil clientUtil,
        ClusterService clusterService,
        Settings settings,
        ADIndexManagement anomalyDetectionIndices,
        NamedXContentRegistry xContentRegistry,
        ADTaskManager adTaskManager,
        SearchFeatureDao searchFeatureDao
    ) {
        super(IndexAnomalyDetectorAction.NAME, transportService, actionFilters, IndexAnomalyDetectorRequest::new);
        this.client = client;
        this.clientUtil = clientUtil;
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.anomalyDetectionIndices = anomalyDetectionIndices;
        this.xContentRegistry = xContentRegistry;
        this.adTaskManager = adTaskManager;
        this.searchFeatureDao = searchFeatureDao;
        filterByEnabled = AnomalyDetectorSettings.AD_FILTER_BY_BACKEND_ROLES.get(settings);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(AD_FILTER_BY_BACKEND_ROLES, it -> filterByEnabled = it);
        this.settings = settings;
    }

    @Override
    protected void doExecute(Task task, IndexAnomalyDetectorRequest request, ActionListener<IndexAnomalyDetectorResponse> actionListener) {
        User user = ParseUtils.getUserContext(client);
        String detectorId = request.getDetectorID();
        RestRequest.Method method = request.getMethod();
        String errorMessage = method == RestRequest.Method.PUT ? FAIL_TO_UPDATE_DETECTOR : FAIL_TO_CREATE_DETECTOR;
        ActionListener<IndexAnomalyDetectorResponse> listener = wrapRestActionListener(actionListener, errorMessage);
        try (ThreadContext.StoredContext context = client.threadPool().getThreadContext().stashContext()) {
            resolveUserAndExecute(detectorId, method, listener, (detector) -> adExecute(request, user, detector, context, listener));
        } catch (Exception e) {
            LOG.error(e);
            listener.onFailure(e);
        }
    }

    private void resolveUserAndExecute(
        String detectorId,
        RestRequest.Method method,
        ActionListener<IndexAnomalyDetectorResponse> listener,
        Consumer<AnomalyDetector> function
    ) {
        try {

            if (method == RestRequest.Method.PUT) {
                // Update detector request, check if user has permissions to update the detector
                // Get detector and verify backend roles
                getConfig(detectorId, listener, function, client, clusterService, xContentRegistry, AnomalyDetector.class);
            } else {
                // Create Detector. No need to get current detector.
                function.accept(null);
            }
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    protected void adExecute(
        IndexAnomalyDetectorRequest request,
        User user,
        AnomalyDetector currentDetector,
        ThreadContext.StoredContext storedContext,
        ActionListener<IndexAnomalyDetectorResponse> listener
    ) {
        anomalyDetectionIndices.update();
        String detectorId = request.getDetectorID();
        long seqNo = request.getSeqNo();
        long primaryTerm = request.getPrimaryTerm();
        WriteRequest.RefreshPolicy refreshPolicy = request.getRefreshPolicy();
        AnomalyDetector detector = request.getDetector();
        RestRequest.Method method = request.getMethod();
        TimeValue requestTimeout = request.getRequestTimeout();
        Integer maxSingleEntityAnomalyDetectors = request.getMaxSingleEntityAnomalyDetectors();
        Integer maxMultiEntityAnomalyDetectors = request.getMaxMultiEntityAnomalyDetectors();
        Integer maxAnomalyFeatures = request.getMaxAnomalyFeatures();
        Integer maxCategoricalFields = request.getMaxCategoricalFields();

        storedContext.restore();
        checkIndicesAndExecute(detector.getIndices(), () -> {
            // Don't replace detector's user when update detector
            // Github issue: https://github.com/opensearch-project/anomaly-detection/issues/124
            // TODO this and similar code should be updated to remove reference to a user

            User detectorUser = currentDetector == null ? user : currentDetector.getUser();
            IndexAnomalyDetectorActionHandler indexAnomalyDetectorActionHandler = new IndexAnomalyDetectorActionHandler(
                clusterService,
                client,
                clientUtil,
                transportService,
                anomalyDetectionIndices,
                detectorId,
                seqNo,
                primaryTerm,
                refreshPolicy,
                detector,
                requestTimeout,
                maxSingleEntityAnomalyDetectors,
                maxMultiEntityAnomalyDetectors,
                maxAnomalyFeatures,
                maxCategoricalFields,
                method,
                xContentRegistry,
                detectorUser,
                adTaskManager,
                searchFeatureDao,
                settings
            );
            indexAnomalyDetectorActionHandler.start(listener);
        }, listener);

        // This call was added to ensure that existing functionality of sharing the resource via backend_role exists
        // TODO 3.0 and later the following must be removed and a new REST API where user must explicitly share the detector should be
        // exposed
        shareResourceWithBackendRoles(detectorId, user, listener);
    }

    private void checkIndicesAndExecute(
        List<String> indices,
        ExecutorFunction function,
        ActionListener<IndexAnomalyDetectorResponse> listener
    ) {
        SearchRequest searchRequest = new SearchRequest()
            .indices(indices.toArray(new String[0]))
            .source(new SearchSourceBuilder().size(1).query(QueryBuilders.matchAllQuery()));
        client.search(searchRequest, ActionListener.wrap(r -> { function.execute(); }, e -> {
            // Due to below issue with security plugin, we get security_exception when invalid index name is mentioned.
            // https://github.com/opendistro-for-elasticsearch/security/issues/718
            LOG.error(e);
            listener.onFailure(e);
        }));
    }
}
