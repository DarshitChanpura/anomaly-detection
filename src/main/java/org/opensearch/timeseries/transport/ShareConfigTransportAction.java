package org.opensearch.timeseries.transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.accesscontrol.resources.ResourceService;
import org.opensearch.accesscontrol.resources.ResourceSharing;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.common.inject.Inject;
import org.opensearch.core.action.ActionListener;
import org.opensearch.tasks.Task;
import org.opensearch.timeseries.TimeSeriesAnalyticsPlugin;
import org.opensearch.timeseries.constant.CommonName;
import org.opensearch.transport.TransportService;

public class ShareConfigTransportAction extends HandledTransportAction<ShareConfigRequest, ShareConfigResponse> {

    private static final Logger log = LogManager.getLogger(ShareConfigTransportAction.class);

    @Inject
    public ShareConfigTransportAction(TransportService transportService, ActionFilters actionFilters) {
        super(ShareConfigAction.NAME, transportService, actionFilters, ShareConfigRequest::new);
    }

    @Override
    protected void doExecute(Task task, ShareConfigRequest request, ActionListener<ShareConfigResponse> listener) {
        ResourceSharing sharing;
        try {
            sharing = shareConfig(request);
            if (sharing == null) {
                log.error("Unable to share resource {}. Check whether security plugin is enabled.", request.getConfigId());
                listener
                    .onResponse(
                        new ShareConfigResponse(
                            "Unable to share resource " + request.getConfigId() + ". Check whether security plugin is enabled."
                        )
                    );
                return;
            }
            log.info("Shared resource {} with {}", request.getConfigId(), sharing.toString());
            listener.onResponse(new ShareConfigResponse("Resource " + request.getConfigId() + " shared successfully with " + sharing));
        } catch (Exception e) {
            log.error("Something went wrong trying to share resource {} with {}", request.getConfigId(), request.getShareWith().toString());
            listener.onFailure(e);
        }
    }

    private ResourceSharing shareConfig(ShareConfigRequest request) {
        ResourceService rs = TimeSeriesAnalyticsPlugin.GuiceHolder.getResourceService();
        return rs.getResourceAccessControlPlugin().shareWith(request.getConfigId(), CommonName.CONFIG_INDEX, request.getShareWith());
    }
}
