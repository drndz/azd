package org.qypp;

import com.azure.core.management.exception.ManagementException;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;
import com.azure.resourcemanager.trafficmanager.TrafficManager;
import com.azure.resourcemanager.trafficmanager.models.TrafficManagerProfile;

/**
 * Shared Azure lookup helper for resources that may or may not exist.
 *
 * <p>This class is not executable. It wraps SDK read calls where a missing
 * resource is reported as HTTP 404 and turns those cases into {@code null}.
 * Other Azure errors, such as permission failures, are rethrown.</p>
 */
final class AzureLookup {
    private AzureLookup() {
    }

    static ResourceGroup resourceGroupOrNull(AzureResourceManager azure, String resourceGroupName) {
        try {
            return azure.resourceGroups().getByName(resourceGroupName);
        } catch (ManagementException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    static TrafficManagerProfile trafficManagerProfileOrNull(
            TrafficManager trafficManager,
            String resourceGroupName,
            String profileName) {
        try {
            return trafficManager.profiles().getByResourceGroup(resourceGroupName, profileName);
        } catch (ManagementException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }
}
