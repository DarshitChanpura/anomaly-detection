/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.timeseries.util;

import java.util.HashSet;
import java.util.Set;

import org.opensearch.accesscontrol.resources.ResourceAccessScope;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.ad.constant.ADResourceScope;

public class ValidationUtil {
    public static ActionRequestValidationException validateScopes(Set<String> scopes) {
        Set<String> validScopes = new HashSet<>();
        for (ADResourceScope scope : ADResourceScope.values()) {
            validScopes.add(scope.name());
        }
        validScopes.add(ResourceAccessScope.READ_ONLY);
        validScopes.add(ResourceAccessScope.READ_WRITE);

        for (String s : scopes) {
            if (!validScopes.contains(s)) {
                ActionRequestValidationException exception = new ActionRequestValidationException();
                exception.addValidationError("Invalid scope: " + s + ". Scope must be one of: " + validScopes);
                return exception;
            }
        }
        return null;
    }
}