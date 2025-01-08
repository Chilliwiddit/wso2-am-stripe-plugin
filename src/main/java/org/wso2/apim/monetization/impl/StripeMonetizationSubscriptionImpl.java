package org.wso2.apim.monetization.impl;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.Token;
import com.stripe.net.RequestOptions;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.apim.monetization.impl.model.MonetizationPlatformCustomer;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.apim.monetization.impl.model.MonetizedSubscription;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.monetization.MonetizationSubscription;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPIProduct;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class StripeMonetizationSubscriptionImpl implements MonetizationSubscription {

    private static final Log log = LogFactory.getLog(StripeMonetizationSubscriptionImpl.class);
    StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
    APIPersistence apiPersistenceInstance;

    @Override
    public void monetizeSubscription(WorkflowDTO workflowDTO, API api) throws WorkflowException {

        SubscriptionWorkflowDTO subWorkFlowDTO;
        Subscriber subscriber;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        Properties properties = new Properties();
        properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
        properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
        Map<String, String> configMap = new HashMap<>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
        apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);

        //read the platform account key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey;
        Organization org = new Organization(workflowDTO.getTenantDomain());
        PublisherAPI publisherAPI = null;
        try {
            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
        } catch (APIPersistenceException e) {
            throw new WorkflowException("Failed to retrieve the API of UUID: " +api.getUUID(), e);
        }
        Map<String, String> monetizationProperties = new Gson().fromJson(publisherAPI.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for API : "
                        + api.getId().getApiName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the stripe connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try (Connection con = APIMgtDBUtil.getConnection()) {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Stripe
            monetizationSharedCustomer = stripeMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Stripe
                monetizationPlatformCustomer = stripeMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    monetizationPlatformCustomer = createMonetizationPlatformCutomer(subscriber);
                }
                monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(), monetizationPlatformCustomer,
                        requestOptions, subWorkFlowDTO);
            }
            //creating Subscriptions
            int apiId = ApiMgtDAO.getInstance().getAPIID(api.getUuid(), con);
            String planId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO, api.getUuid());
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Could not monetize subscription for API : " + subWorkFlowDTO.getApiName()
                    + " by Application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error while retrieving the API ID";
            throw new WorkflowException(errorMessage, e);
        }
    }

    @Override
    public void monetizeSubscription(org.wso2.carbon.apimgt.impl.dto.WorkflowDTO workflowDTO, APIProduct apiProduct) throws WorkflowException {

        SubscriptionWorkflowDTO subWorkFlowDTO;
        Subscriber subscriber;
        MonetizationPlatformCustomer monetizationPlatformCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer;
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;

        Properties properties = new Properties();
        properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
        properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
        Map<String, String> configMap = new HashMap<>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
        apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);

        //read the platform account key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        String connectedAccountKey;
        PublisherAPIProduct product;
        try {
            product = apiPersistenceInstance.getPublisherAPIProduct(new Organization(workflowDTO.getTenantDomain()),
                    apiProduct.getUuid());
        } catch (APIPersistenceException e) {
            throw new WorkflowException("Failed to retrieve the Product of UUID: " + apiProduct.getUuid(), e);
        }
        Map<String, String> monetizationProperties = new Gson().fromJson(product.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : "
                        + apiProduct.getId().getName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to create artifacts in the stripe connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            subscriber = apiMgtDAO.getSubscriber(subWorkFlowDTO.getSubscriber());
            // check whether the application is already registered as a customer under the particular
            // APIprovider/Connected Account in Stripe
            monetizationSharedCustomer = stripeMonetizationDAO.getSharedCustomer(subWorkFlowDTO.getApplicationId(),
                    subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getTenantId());
            if (monetizationSharedCustomer.getSharedCustomerId() == null) {
                // checks whether the subscriber is already registered as a customer Under the
                // tenant/Platform account in Stripe
                monetizationPlatformCustomer = stripeMonetizationDAO.getPlatformCustomer(subscriber.getId(),
                        subscriber.getTenantId());
                if (monetizationPlatformCustomer.getCustomerId() == null) {
                    monetizationPlatformCustomer = createMonetizationPlatformCutomer(subscriber);
                }
                monetizationSharedCustomer = createSharedCustomer(subscriber.getEmail(), monetizationPlatformCustomer,
                        requestOptions, subWorkFlowDTO);
            }
            //creating Subscriptions
            int apiId = ApiMgtDAO.getInstance().getAPIProductId(apiProduct.getId());
            String planId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, subWorkFlowDTO.getTierName());
            createMonetizedSubscriptions(planId, monetizationSharedCustomer, requestOptions, subWorkFlowDTO, apiProduct.getUuid());
        } catch (APIManagementException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Could not monetize subscription for : " + subWorkFlowDTO.getApiName()
                    + " by application " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, e);
        }
    }

    @Override
    public void deleteMonetizedSubscription(org.wso2.carbon.apimgt.impl.dto.WorkflowDTO workflowDTO, API api) throws WorkflowException {

        SubscriptionWorkflowDTO subWorkflowDTO;
        MonetizedSubscription monetizedSubscription;
        StripeMonetizationDAO stripeMonetizationDAO = new StripeMonetizationDAO();
        subWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        Properties properties = new Properties();
        properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
        properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
        Map<String, String> configMap = new HashMap<>();
        Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
        if (configs != null && !configs.isEmpty()) {
            configMap.putAll(configs);
        }
        configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
        apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);
        //read the platform key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkflowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Organization org = new Organization(workflowDTO.getTenantDomain());
        PublisherAPI publisherAPI = null;
        try {
            publisherAPI = apiPersistenceInstance.getPublisherAPI(org, api.getUUID());
        } catch (APIPersistenceException e) {
            throw new WorkflowException("Failed to retrieve the API of UUID: " +api.getUUID(), e);
        }
        Map<String, String> monetizationProperties = new Gson().fromJson(publisherAPI.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            // get the key of the connected account
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : "
                        + api.getId().getApiName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to add,remove artifacts in connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            //get the stripe subscription id
            monetizedSubscription = stripeMonetizationDAO
                    .getMonetizedSubscription(api.getUuid(), subWorkflowDTO.getApiName(),
                            subWorkflowDTO.getApplicationId(), subWorkflowDTO.getTenantDomain());
        } catch (StripeMonetizationException ex) {
            String errorMessage = "Could not retrieve monetized subscription info for : "
                    + subWorkflowDTO.getApplicationName() + " by Application : " + subWorkflowDTO.getApplicationName();
            throw new WorkflowException(errorMessage, ex);
        }
        if (monetizedSubscription.getSubscriptionId() != null) {
            try {
                Subscription subscription = Subscription.retrieve(monetizedSubscription.getSubscriptionId(),
                        requestOptions);
                Map<String, Object> params = new HashMap<>();
                //canceled subscription will be invoiced immediately
                params.put(StripeMonetizationConstants.INVOICE_NOW, true);
                subscription = subscription.cancel(params, requestOptions);
                if (StringUtils.equals(subscription.getStatus(), StripeMonetizationConstants.CANCELED)) {
                    stripeMonetizationDAO.removeMonetizedSubscription(monetizedSubscription.getId());
                }
                if (log.isDebugEnabled()) {
                    String msg = "Monetized subscriprion for : " + subWorkflowDTO.getApiName()
                            + " by Application : " + subWorkflowDTO.getApplicationName() + " is removed successfully ";
                    log.debug(msg);
                }
            } catch (StripeException ex) {
                String errorMessage = "Failed to remove subcription in billing engine for : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            } catch (StripeMonetizationException ex) {
                String errorMessage = "Failed to remove monetization subcription info from DB of : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            }
        }
    }

    @Override
    public void deleteMonetizedSubscription(org.wso2.carbon.apimgt.impl.dto.WorkflowDTO workflowDTO, APIProduct apiProduct) throws WorkflowException {

        SubscriptionWorkflowDTO subWorkflowDTO;
        MonetizedSubscription monetizedSubscription;
        StripeMonetizationDAO stripeMonetizationDAO = new StripeMonetizationDAO();
        subWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        //read the platform key of Stripe
        Stripe.apiKey = getPlatformAccountKey(subWorkflowDTO.getTenantId());
        String connectedAccountKey = StringUtils.EMPTY;
        Map<String, String> monetizationProperties = new Gson().fromJson(apiProduct.getMonetizationProperties().toString(),
                HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            // get the key of the connected account
            connectedAccountKey = monetizationProperties.get
                    (StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(connectedAccountKey)) {
                String errorMessage = "Connected account stripe key was not found for : " + apiProduct.getId().getName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage);
            }
        } else {
            String errorMessage = "Stripe key of the connected account is empty.";
            log.error(errorMessage);
            throw new WorkflowException(errorMessage);
        }
        //needed to add,remove artifacts in connected account
        RequestOptions requestOptions = RequestOptions.builder().setStripeAccount(connectedAccountKey).build();
        try {
            //get the stripe subscription id
            monetizedSubscription = stripeMonetizationDAO
                    .getMonetizedSubscription(apiProduct.getUuid(), subWorkflowDTO.getApiName(),
                            subWorkflowDTO.getApplicationId(), subWorkflowDTO.getTenantDomain());
        } catch (StripeMonetizationException ex) {
            String errorMessage = "Could not retrieve monetized subscription info for : "
                    + subWorkflowDTO.getApplicationName() + " by application : " + subWorkflowDTO.getApplicationName();
            throw new WorkflowException(errorMessage, ex);
        }
        if (monetizedSubscription.getSubscriptionId() != null) {
            try {
                Subscription subscription = Subscription.retrieve(monetizedSubscription.getSubscriptionId(),
                        requestOptions);
                Map<String, Object> params = new HashMap<>();
                //canceled subscription will be invoiced immediately
                params.put(StripeMonetizationConstants.INVOICE_NOW, true);
                subscription = subscription.cancel(params, requestOptions);
                if (StringUtils.equals(subscription.getStatus(), StripeMonetizationConstants.CANCELED)) {
                    stripeMonetizationDAO.removeMonetizedSubscription(monetizedSubscription.getId());
                }
                if (log.isDebugEnabled()) {
                    String msg = "Monetized subscriprion for : " + subWorkflowDTO.getApiName()
                            + " by application : " + subWorkflowDTO.getApplicationName() + " is removed successfully ";
                    log.debug(msg);
                }
            } catch (StripeException ex) {
                String errorMessage = "Failed to remove subcription in billing engine for : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            } catch (StripeMonetizationException ex) {
                String errorMessage = "Failed to remove monetization subcription info from DB of : "
                        + subWorkflowDTO.getApiName() + " by Application : " + subWorkflowDTO.getApplicationName();
                log.error(errorMessage);
                throw new WorkflowException(errorMessage, ex);
            }
        }
    }

    public MonetizationPlatformCustomer createMonetizationPlatformCutomer(Subscriber subscriber)
            throws WorkflowException {

        MonetizationPlatformCustomer monetizationPlatformCustomer = new MonetizationPlatformCustomer();
        Customer customer = null;
        try {
            Map<String, Object> customerParams = new HashMap<String, Object>();
            //Customer object in billing engine will be created without the email id
            if (!StringUtils.isEmpty(subscriber.getEmail())) {
                customerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, subscriber.getEmail());
            }
            customerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION, "Customer for "
                    + subscriber.getName());
            customerParams.put(StripeMonetizationConstants.CUSTOMER_SOURCE, StripeMonetizationConstants.DEFAULT_TOKEN);
            //create a customer for subscriber in the platform account
            customer = Customer.create(customerParams);
            monetizationPlatformCustomer.setCustomerId(customer.getId());
            try {
                //returns the id of the inserted record
                int id = stripeMonetizationDAO.addBEPlatformCustomer(subscriber.getId(), subscriber.getTenantId(),
                        customer.getId());
                monetizationPlatformCustomer.setId(id);
            } catch (StripeMonetizationException e) {
                if (customer != null) {
                    // deletes the customer if the customer is created in Stripe and failed to update in DB
                    customer.delete();
                }
                String errorMsg = "Error when inserting stripe customer details of " + subscriber.getName()
                        + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
        } catch (StripeException ex) {
            String errorMsg = "Error while creating a customer in Stripe for " + subscriber.getName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationPlatformCustomer;
    }

    public MonetizationSharedCustomer createSharedCustomer(String email, MonetizationPlatformCustomer platformCustomer,
                                                           RequestOptions requestOptions,
                                                           SubscriptionWorkflowDTO subWorkFlowDTO)
            throws WorkflowException {

        Customer stripeCustomer;
        MonetizationSharedCustomer monetizationSharedCustomer = new MonetizationSharedCustomer();
        Token token;
        try {
            Map<String, Object> params = new HashMap<String, Object>();
            params.put(StripeMonetizationConstants.CUSTOMER, platformCustomer.getCustomerId());
            //creating a token using the platform customers source
            token = Token.create(params, requestOptions);
        } catch (StripeException ex) {
            String errorMsg = "Error when creating a stripe token for" + platformCustomer.getSubscriberName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        Map<String, Object> sharedCustomerParams = new HashMap<>();
        //if the email id of subscriber is empty, a customer object in billing engine will be created without email id
        if (!StringUtils.isEmpty(email)) {
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_EMAIL, email);
        }
        try {
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_DESCRIPTION, "Shared Customer for "
                    + subWorkFlowDTO.getApplicationName() + StripeMonetizationConstants.FILE_SEPERATOR
                    + subWorkFlowDTO.getSubscriber());
            sharedCustomerParams.put(StripeMonetizationConstants.CUSTOMER_SOURCE, token.getId());
            stripeCustomer = Customer.create(sharedCustomerParams, requestOptions);
            try {
                monetizationSharedCustomer.setApplicationId(subWorkFlowDTO.getApplicationId());
                monetizationSharedCustomer.setApiProvider(subWorkFlowDTO.getApiProvider());
                monetizationSharedCustomer.setTenantId(subWorkFlowDTO.getTenantId());
                monetizationSharedCustomer.setSharedCustomerId(stripeCustomer.getId());
                monetizationSharedCustomer.setParentCustomerId(platformCustomer.getId());
                //returns the ID of the inserted record
                int id = stripeMonetizationDAO.addBESharedCustomer(monetizationSharedCustomer);
                monetizationSharedCustomer.setId(id);
            } catch (StripeMonetizationException ex) {
                //deleting the created customer in stripe if it fails to create the DB record
                stripeCustomer.delete(requestOptions);
                String errorMsg = "Error when inserting Stripe shared customer details of Application : "
                        + subWorkFlowDTO.getApplicationName() + "to database";
                log.error(errorMsg, ex);
                throw new WorkflowException(errorMsg, ex);
            }
            if (log.isDebugEnabled()) {
                String msg = "A customer for Application " + subWorkFlowDTO.getApplicationName()
                        + " is created under the " + subWorkFlowDTO.getApiProvider()
                        + "'s connected account in Stripe";
                log.debug(msg);
            }
        } catch (StripeException ex) {
            String errorMsg = "Error while creating a shared customer in Stripe for Application : "
                    + subWorkFlowDTO.getApplicationName();
            log.error(errorMsg);
            throw new WorkflowException(errorMsg, ex);
        }
        return monetizationSharedCustomer;
    }

    private String getPlatformAccountKey(int tenantId) throws WorkflowException {

        String stripePlatformAccountKey = null;
        String tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        try {
            //get the stripe key of platform account from  tenant conf json file
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    stripePlatformAccountKey = monetizationInfo
                            .get(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                    if (StringUtils.isBlank(stripePlatformAccountKey)) {
                        String errorMessage = "Stripe platform account key is empty for tenant : " + tenantDomain;
                        throw new WorkflowException(errorMessage);
                    }
                    return stripePlatformAccountKey;
                }
            }
        } catch (APIManagementException e) {
            throw new WorkflowException("Failed to get the configuration for tenant from DB:  " + tenantDomain, e);
        }
        return stripePlatformAccountKey;
    }

    public void createMonetizedSubscriptions(String planId, MonetizationSharedCustomer sharedCustomer,
                                             RequestOptions requestOptions, SubscriptionWorkflowDTO subWorkFlowDTO, String apiUuid)
            throws WorkflowException {

        StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
        APIIdentifier identifier = new APIIdentifier(subWorkFlowDTO.getApiProvider(), subWorkFlowDTO.getApiName(),
                subWorkFlowDTO.getApiVersion());
        Subscription subscription = null;
        try {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put(StripeMonetizationConstants.PLAN, planId);
            Map<String, Object> items = new HashMap<String, Object>();
            //adding a subscription item, with an attached plan.
            items.put("0", item);
            Map<String, Object> subParams = new HashMap<String, Object>();
            subParams.put(StripeMonetizationConstants.CUSTOMER, sharedCustomer.getSharedCustomerId());
            subParams.put(StripeMonetizationConstants.ITEMS, items);
            try {
                //create a subscription in stripe under the API Providers Connected Account
                subscription = Subscription.create(subParams, requestOptions);
            } catch (StripeException ex) {
                String errorMsg = "Error when adding a subscription in Stripe for Application : " +
                        subWorkFlowDTO.getApplicationName();
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, ex);
            }
            try {
                stripeMonetizationDAO.addBESubscription(identifier, subWorkFlowDTO.getApplicationId(),
                        subWorkFlowDTO.getTenantId(), sharedCustomer.getId(), subscription.getId(), apiUuid);
            } catch (StripeMonetizationException e) {
                //delete the subscription in Stripe, if the entry to database fails in API Manager
                subscription.cancel((Map<String, Object>) null, requestOptions);
                String errorMsg = "Error when adding stripe subscription details of Application "
                        + subWorkFlowDTO.getApplicationName() + " to Database";
                log.error(errorMsg);
                throw new WorkflowException(errorMsg, e);
            }
            if (log.isDebugEnabled()) {
                String msg = "Stripe subscription for " + subWorkFlowDTO.getApplicationName() + " is created for"
                        + subWorkFlowDTO.getApiName() + " API";
                log.debug(msg);
            }
        } catch (StripeException ex) {
            String errorMessage = "Failed to create subscription in Stripe for API : " + subWorkFlowDTO.getApiName()
                    + "by Application : " + subWorkFlowDTO.getApplicationName();
            log.error(errorMessage);
            throw new WorkflowException(errorMessage, ex);
        }
    }
}
