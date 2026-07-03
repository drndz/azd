package org.qypp;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.AzureResourceManager;
import com.azure.resourcemanager.trafficmanager.TrafficManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Properties;

/**
 * Executable entry point for the Azure management sample.
 *
 * <p>This is the only class with a {@code main} method. It loads
 * {@code conf/conf.properties}, authenticates with Azure using the configured
 * service principal, then delegates to the creator, deleter, and representation
 * classes according to the config flags.</p>
 *
 * <p>Run through the Bash scripts in {@code scripts/}; see {@code README.md}.</p>
 */
public class AzureInfraTool {
    public static void main(String[] args) throws IOException {
        Properties config = AzureConfig.load();

        String tenantId = AzureConfig.required(config, "azure_tenant_id");
        String subscriptionId = AzureConfig.required(config, "azure_subscription_id");
        String clientId = AzureConfig.firstPresent(config, "azure_client_id", "application_id");
        String clientSecret = AzureConfig.firstPresent(config, "azure_client_secret", "azure_secret_val");

        TokenCredential credential = new ClientSecretCredentialBuilder()
                .tenantId(tenantId)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();

        AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
        AzureResourceManager azure = AzureResourceManager
                .authenticate(credential, profile)
                .withSubscription(subscriptionId);
        TrafficManager trafficManager = TrafficManager.authenticate(credential, profile);

        System.out.printf("Connected to Azure subscription %s%n%n", subscriptionId);

        if (Boolean.parseBoolean(AzureConfig.value(config, "azure_delete_demo"))) {
            new AzureInfraDeleter(azure, config).deleteConfiguredDemoResources();
            return;
        }

        if (Boolean.parseBoolean(AzureConfig.value(config, "azure_budget_create"))) {
            try {
                createOrUpdateSubscriptionBudget(credential, subscriptionId, config);
            } catch (RuntimeException e) {
                System.out.printf("Budget create/update skipped: %s%n%n", e.getMessage());
            }
        }

        AzureInfraCreator creator = new AzureInfraCreator(azure, trafficManager, config, credential, subscriptionId);
        if (Boolean.parseBoolean(AzureConfig.value(config, "azure_full_lb_demo_recreate"))) {
            creator.recreateFullLoadBalancedTrafficManagerDemo();
        }

        if (Boolean.parseBoolean(AzureConfig.value(config, "azure_create_demo"))) {
            creator.createResourceGroup();
        }

        if (Boolean.parseBoolean(AzureConfig.value(config, "azure_traffic_manager_create"))) {
            creator.createTrafficManagerProfile();
        }

        AzureRepresentationGenerator representationGenerator =
                new AzureRepresentationGenerator(azure, trafficManager, config);
        representationGenerator.listConfiguredDemoResources();
        new AzureRecursiveTopologyLister(azure, trafficManager, config).listFromConfiguredTrafficManager();
        representationGenerator.writeConfiguredDemoGraph();

        listResourceGroups(azure);
        listPublicIpAddresses(azure);
        listVirtualNetworks(azure);
        listLoadBalancers(azure);
        listTrafficManagerProfiles(trafficManager);
    }

    private static void createOrUpdateSubscriptionBudget(
            TokenCredential credential,
            String subscriptionId,
            Properties config) throws IOException {
        String budgetName = AzureConfig.required(config, "azure_budget_name");
        BigDecimal amountUsd = new BigDecimal(AzureConfig.required(config, "azure_budget_amount_usd"));
        String contactEmail = AzureConfig.required(config, "azure_budget_contact_email");
        LocalDate startDate = LocalDate.now().withDayOfMonth(1);

        String accessToken = credential
                .getToken(new TokenRequestContext().addScopes("https://management.azure.com/.default"))
                .block()
                .getToken();

        String scope = "/subscriptions/" + subscriptionId;
        String budgetPath = scope + "/providers/Microsoft.CostManagement/budgets/"
                + URLEncoder.encode(budgetName, StandardCharsets.UTF_8);
        URI uri = URI.create("https://management.azure.com" + budgetPath + "?api-version=2023-11-01");

        String requestBody = """
                {
                  "properties": {
                    "category": "Cost",
                    "amount": %s,
                    "timeGrain": "Monthly",
                    "timePeriod": {
                      "startDate": "%sT00:00:00Z"
                    },
                    "notifications": {
                      "Actual_GreaterThan_50_Percent": {
                        "enabled": true,
                        "operator": "GreaterThan",
                        "threshold": 50,
                        "thresholdType": "Actual",
                        "contactEmails": ["%s"]
                      },
                      "Actual_GreaterThan_80_Percent": {
                        "enabled": true,
                        "operator": "GreaterThan",
                        "threshold": 80,
                        "thresholdType": "Actual",
                        "contactEmails": ["%s"]
                      },
                      "Actual_GreaterThan_100_Percent": {
                        "enabled": true,
                        "operator": "GreaterThan",
                        "threshold": 100,
                        "thresholdType": "Actual",
                        "contactEmails": ["%s"]
                      }
                    }
                  }
                }
                """.formatted(
                amountUsd.toPlainString(),
                startDate,
                AzureConfig.jsonEscape(contactEmail),
                AzureConfig.jsonEscape(contactEmail),
                AzureConfig.jsonEscape(contactEmail));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Budget create/update failed. HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            System.out.printf("Created or updated budget: %s ($%s/month)%n%n",
                    budgetName,
                    amountUsd.toPlainString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating Azure budget", e);
        }
    }

    private static void listResourceGroups(AzureResourceManager azure) {
        System.out.println("Resource groups:");
        azure.resourceGroups().list().forEach(resourceGroup ->
                System.out.printf("- %s [%s]%n", resourceGroup.name(), resourceGroup.regionName()));
        System.out.println();
    }

    private static void listPublicIpAddresses(AzureResourceManager azure) {
        System.out.println("Public IP addresses:");
        azure.publicIpAddresses().list().forEach(publicIp ->
                System.out.printf("- %s / rg=%s / ip=%s / region=%s%n",
                        publicIp.name(),
                        publicIp.resourceGroupName(),
                        publicIp.ipAddress(),
                        publicIp.regionName()));
        System.out.println();
    }

    private static void listVirtualNetworks(AzureResourceManager azure) {
        System.out.println("Virtual networks:");
        azure.networks().list().forEach(network ->
                System.out.printf("- %s / rg=%s / addressSpaces=%s / region=%s%n",
                        network.name(),
                        network.resourceGroupName(),
                        network.addressSpaces(),
                        network.regionName()));
        System.out.println();
    }

    private static void listLoadBalancers(AzureResourceManager azure) {
        System.out.println("Load balancers:");
        azure.loadBalancers().list().forEach(loadBalancer ->
                System.out.printf("- %s / rg=%s / region=%s / frontends=%s%n",
                        loadBalancer.name(),
                        loadBalancer.resourceGroupName(),
                        loadBalancer.regionName(),
                        loadBalancer.frontends().keySet()));
        System.out.println();
    }

    private static void listTrafficManagerProfiles(TrafficManager trafficManager) {
        System.out.println("Traffic Manager profiles:");
        trafficManager.profiles().list().forEach(profile ->
                System.out.printf("- %s / rg=%s / fqdn=%s / routing=%s / monitor=%s%n",
                        profile.name(),
                        profile.resourceGroupName(),
                        profile.fqdn(),
                        profile.trafficRoutingMethod(),
                        profile.monitorStatus()));
        System.out.println();
    }
}
