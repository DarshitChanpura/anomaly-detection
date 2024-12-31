package org.opensearch.ad.constant;

import org.opensearch.accesscontrol.resources.ResourceAccessScope;

public enum ADResourceScope implements ResourceAccessScope {
    AD_READ_ACCESS("ad_read_access"),
    AD_FULL_ACCESS("ad_full_access");

    private final String scopeName;

    ADResourceScope(String scopeName) {
        this.scopeName = scopeName;
    }

    public String getScopeName() {
        return scopeName;
    }
}
