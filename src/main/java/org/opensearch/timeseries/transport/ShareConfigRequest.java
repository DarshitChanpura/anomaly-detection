package org.opensearch.timeseries.transport;

import java.io.IOException;
import java.util.stream.Collectors;

import org.opensearch.accesscontrol.resources.ShareWith;
import org.opensearch.accesscontrol.resources.SharedWithScope;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.timeseries.util.ValidationUtil;

public class ShareConfigRequest extends ActionRequest {
    private final String configId;
    private final ShareWith shareWith;

    public ShareConfigRequest(String configId, ShareWith shareWith) {
        this.configId = configId;
        this.shareWith = shareWith;
    }

    public ShareConfigRequest(StreamInput in) throws IOException {
        this.configId = in.readString();
        this.shareWith = in.readNamedWriteable(ShareWith.class);
    }

    @Override
    public void writeTo(final StreamOutput out) throws IOException {
        out.writeString(configId);
        out.writeNamedWriteable(shareWith);
    }

    @Override
    public ActionRequestValidationException validate() {

        return ValidationUtil
            .validateScopes(shareWith.getSharedWithScopes().stream().map(SharedWithScope::getScope).collect(Collectors.toSet()));
    }

    public String getConfigId() {
        return configId;
    }

    public ShareWith getShareWith() {
        return shareWith;
    }
}
