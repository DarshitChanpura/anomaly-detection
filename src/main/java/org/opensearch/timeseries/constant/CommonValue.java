/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.timeseries.constant;

public class CommonValue {
    // unknown or no schema version
    public static Integer NO_SCHEMA_VERSION = 0;

    // config access control
    public static String CONFIG_ACCESS_CONTROL_BASE_ACTION = "cluster:admin/timeseries/config/access";
    public static String CONFIG_ACCESS_CONTROL_BASE_URI = "/_plugins/_timeseries/config/access";
}
