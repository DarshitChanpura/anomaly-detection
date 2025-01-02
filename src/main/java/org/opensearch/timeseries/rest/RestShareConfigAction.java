package org.opensearch.timeseries.rest;

import static java.util.Collections.singletonList;
import static org.opensearch.rest.RestRequest.Method.POST;
import static org.opensearch.timeseries.constant.CommonValue.CONFIG_ACCESS_CONTROL_BASE_URI;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.opensearch.accesscontrol.resources.ShareWith;
import org.opensearch.client.node.NodeClient;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.rest.BaseRestHandler;
import org.opensearch.rest.RestRequest;
import org.opensearch.rest.action.RestToXContentListener;
import org.opensearch.timeseries.transport.ShareConfigAction;
import org.opensearch.timeseries.transport.ShareConfigRequest;

/**
 * Registers REST API to handle detector/forecaster sharing.
 * Here is an example request:
 *
 */
public class RestShareConfigAction extends BaseRestHandler {
    public RestShareConfigAction() {}

    @Override
    public List<Route> routes() {
        return singletonList(new Route(POST, CONFIG_ACCESS_CONTROL_BASE_URI + "/share"));
    }

    @Override
    public String getName() {
        return "share_timeseries_config";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> source;
        try (XContentParser parser = request.contentParser()) {
            source = parser.map();
        }

        String resourceId = (String) source.get("config_id");

        ShareWith shareWith = parseShareWith(source);
        final ShareConfigRequest shareResourceRequest = new ShareConfigRequest(resourceId, shareWith);
        return channel -> client.executeLocally(ShareConfigAction.INSTANCE, shareResourceRequest, new RestToXContentListener<>(channel));
    }

    private ShareWith parseShareWith(Map<String, Object> source) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> shareWithMap = (Map<String, Object>) source.get("share_with");
        if (shareWithMap == null || shareWithMap.isEmpty()) {
            throw new IllegalArgumentException("share_with is required and cannot be empty");
        }

        String jsonString = XContentFactory.jsonBuilder().map(shareWithMap).toString();

        try (
            XContentParser parser = XContentType.JSON
                .xContent()
                .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, jsonString)
        ) {
            return ShareWith.fromXContent(parser);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid share_with structure: " + e.getMessage(), e);
        }
    }
}
