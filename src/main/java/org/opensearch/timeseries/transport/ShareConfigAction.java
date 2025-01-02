package org.opensearch.timeseries.transport;

import static org.opensearch.timeseries.constant.CommonValue.CONFIG_ACCESS_CONTROL_BASE_ACTION;

import org.opensearch.action.ActionType;

public class ShareConfigAction extends ActionType<ShareConfigResponse> {

    /**
     * Share config action instance.
     */
    public static final ShareConfigAction INSTANCE = new ShareConfigAction();
    /**
     * Share config action name
     */
    public static final String NAME = CONFIG_ACCESS_CONTROL_BASE_ACTION + "/share";

    private ShareConfigAction() {
        super(NAME, ShareConfigResponse::new);
    }
}
