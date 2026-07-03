package org.qypp;

import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.resources.models.ResourceGroup;

import java.util.Properties;

/**
 * Deletes all demo infrastructure by deleting the configured resource group.
 *
 * <p>This class is not executable by itself. {@link AzureInfraTool} constructs it and
 * calls {@link #deleteConfiguredDemoResources()} when
 * {@code azure_delete_demo=true}. The method deletes only
 * {@code azure_resource_group}; any resources inside that group are removed by
 * Azure as part of resource-group deletion.</p>
 */
public class AzureInfraDeleter {
    private final AzureResourceManager azure;
    private final Properties config;

    public AzureInfraDeleter(AzureResourceManager azure, Properties config) {
        this.azure = azure;
        this.config = config;
    }

    public void deleteConfiguredDemoResources() {
        String resourceGroupName = AzureConfig.required(config, "azure_resource_group");
        ResourceGroup resourceGroup = AzureLookup.resourceGroupOrNull(azure, resourceGroupName);
        if (resourceGroup == null) {
            System.out.printf("Resource group already absent: %s%n", resourceGroupName);
            return;
        }

        System.out.printf("Deleting resource group and contained demo resources: %s%n", resourceGroupName);
        azure.resourceGroups().deleteByName(resourceGroupName);
        System.out.printf("Deleted resource group: %s%n", resourceGroupName);
    }
}
