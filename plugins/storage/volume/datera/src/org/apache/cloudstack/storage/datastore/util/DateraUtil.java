// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.datastore.util;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
//import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
//import org.apache.http.impl.conn.BasicClientConnectionManager;
//import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public class DateraUtil {

    private static final Logger s_logger = Logger.getLogger(DateraUtil.class);
    private static final String API_VERSION = "v2";

    public static final String PROVIDER_NAME = "Datera";

    private static final String HEADER_AUTH_TOKEN = "auth-token";
    private static final String HEADER_CONTENT_TYPE = "Content-type";
    private static final String HEADER_VALUE_JSON = "application/json";

    public static final String MANAGEMENT_VIP = "mVip";
    public static final String STORAGE_VIP = "sVip";

    public static final String MANAGEMENT_PORT = "mPort";
    public static final String STORAGE_PORT = "sPort";

    private static final int DEFAULT_MANAGEMENT_PORT = 7717;
    private static final int DEFAULT_STORAGE_PORT = 3260;
    private static final int DEFAULT_NUM_REPLICAS = 3;

    private static final String DEFAULT_VOL_PLACEMENT = "hybrid";

    public static final String CLUSTER_ADMIN_USERNAME = "clusterAdminUsername";
    public static final String CLUSTER_ADMIN_PASSWORD = "clusterAdminPassword";

    public static final String CLUSTER_DEFAULT_MIN_IOPS = "clusterDefaultMinIops";
    public static final String CLUSTER_DEFAULT_MAX_IOPS = "clusterDefaultMaxIops";
    public static final String NUM_REPLICAS = "numReplicas";
    public static final String VOL_PLACEMENT = "volPlacement";

    public static final String STORAGE_POOL_ID = "DateraStoragePoolId";
    public static final String VOLUME_SIZE = "DateraVolumeSize";
    public static final String VOLUME_ID = "DateraVolumeId";
    public static final long POLL_TIMEOUT_MS = 3000;
    public static final int DEFAULT_RETRIES = 3;

    private static Gson gson = new GsonBuilder().create();
    public static final String INITIATOR_GROUP_PREFIX = "Cloudstack-InitiatorGroup";
    public static final String INITIATOR_PREFIX = "Cloudstack-Initiator";
    public static final String APPINSTANCE_PREFIX = "Cloudstack";

    private int managementPort;
    private String managementIp;
    private String username;
    private String password;

    private static final String SCHEME_HTTP = "http";

    public DateraUtil(String managementIp, int managementPort, String username, String password) {
        this.managementPort = managementPort;
        this.managementIp = managementIp;
        this.username = username;
        this.password = password;
    }

    public static String login(DateraObject.DateraConnection conn) throws UnsupportedEncodingException, DateraObject.DateraError {

        DateraObject.DateraLogin loginParams = new DateraObject.DateraLogin(conn.getUsername(), conn.getPassword());
        HttpPut loginReq = new HttpPut(generateApiUrl("login"));

        StringEntity jsonParams = new StringEntity(gson.toJson(loginParams));
        loginReq.setEntity(jsonParams);

        String response = executeHttp(conn, loginReq);
        DateraObject.DateraLoginResponse loginResponse = gson.fromJson(response, DateraObject.DateraLoginResponse.class);

        return loginResponse.getKey();

    }

    public static Map<String, DateraObject.AppInstance> getAppInstances(DateraObject.DateraConnection conn) throws DateraObject.DateraError {

        HttpGet getAppInstancesReq = new HttpGet(generateApiUrl("app_instances"));
        String response = null;

        response = executeApiRequest(conn, getAppInstancesReq);

        Type responseType = new TypeToken<Map<String, DateraObject.AppInstance>>() {}.getType();

        return gson.fromJson(response, responseType);
    }

    public static DateraObject.AppInstance getAppInstance(DateraObject.DateraConnection conn, String name) throws DateraObject.DateraError {

        HttpGet url = new HttpGet(generateApiUrl("app_instances", name));

        String response = null;
        try {
            response = executeApiRequest(conn, url);
            return gson.fromJson(response, DateraObject.AppInstance.class);
        } catch (DateraObject.DateraError dateraError) {
            if (DateraObject.DateraErrorTypes.NotFoundError.equals(dateraError)){
                return null;
            } else {
                throw dateraError;
            }
        }
    }

    public static void updateAppInstanceIops(DateraObject.DateraConnection conn, String appInstance, int totalIops) throws UnsupportedEncodingException, DateraObject.DateraError {

        HttpPut url = new HttpPut(generateApiUrl(
                "app_instances", appInstance,
                "storage_instances", DateraObject.DEFAULT_STORAGE_NAME,
                "volumes", DateraObject.DEFAULT_VOLUME_NAME,
                "performance_policy"));

        DateraObject.PerformancePolicy performancePolicy = new DateraObject.PerformancePolicy(totalIops);

        url.setEntity(new StringEntity(gson.toJson(performancePolicy)));
        executeApiRequest(conn, url);

    }

    public static void updateAppInstanceSize(DateraObject.DateraConnection conn, String appInstanceName, int newSize) throws UnsupportedEncodingException, DateraObject.DateraError {
        try {

        updateAppInstanceAdminState(conn, appInstanceName, DateraObject.AppState.OFFLINE);
        HttpPut url = new HttpPut(generateApiUrl(
                "app_instances", appInstanceName,
                "storage_instances", DateraObject.DEFAULT_STORAGE_NAME,
                "volumes", DateraObject.DEFAULT_VOLUME_NAME));

        DateraObject.Volume volume = new DateraObject.Volume(newSize);
        url.setEntity(new StringEntity(gson.toJson(volume)));
        executeApiRequest(conn, url);


        } catch (DateraObject.DateraError dateraError) {
            throw dateraError;
        } catch (UnsupportedEncodingException dateraError) {
            throw dateraError;
        } finally {
            // bring it back online if something bad happend
            try {
                updateAppInstanceAdminState(conn, appInstanceName, DateraObject.AppState.ONLINE);
            } catch (Exception e){
                s_logger.warn("Error getting appInstance " + appInstanceName + " back online ", e);
            }
        }
    }



    private static DateraObject.AppInstance createAppInstance(DateraObject.DateraConnection conn, String name, StringEntity appInstanceEntity) throws DateraObject.DateraError {

        HttpPost createAppInstance = new HttpPost(generateApiUrl("app_instances"));
        HttpGet getAppInstance = new HttpGet(generateApiUrl("app_instances", name));
        createAppInstance.setEntity(appInstanceEntity);
        String response = null;

        executeApiRequest(conn, createAppInstance);

        //create is async, do a get to fetch the IQN
        response = executeApiRequest(conn, getAppInstance);

        return gson.fromJson(response, DateraObject.AppInstance.class);
    }

    public static DateraObject.AppInstance createAppInstance(DateraObject.DateraConnection conn, String name, int size, int totalIops, int replicaCount) throws UnsupportedEncodingException, DateraObject.DateraError {

        DateraObject.AppInstance appInstance = new DateraObject.AppInstance(name, size, totalIops, replicaCount);
        StringEntity appInstanceEntity = new StringEntity(gson.toJson(appInstance));

        return createAppInstance(conn, name, appInstanceEntity);
    }

    public static DateraObject.AppInstance createAppInstance(DateraObject.DateraConnection conn, String name, int size, int totalIops, int replicaCount, String placementMode) throws UnsupportedEncodingException, DateraObject.DateraError {

        DateraObject.AppInstance appInstance = new DateraObject.AppInstance(name, size, totalIops, replicaCount, placementMode);
        StringEntity appInstanceEntity = new StringEntity(gson.toJson(appInstance));

        return createAppInstance(conn, name, appInstanceEntity);
    }


    public static DateraObject.AppInstance cloneAppInstance(DateraObject.DateraConnection conn, String name, String srcCloneName) throws UnsupportedEncodingException, DateraObject.DateraError {

        DateraObject.AppInstance appInstanceObj = new DateraObject.AppInstance(name, srcCloneName);

        StringEntity appInstanceEntity = new StringEntity(gson.toJson(appInstanceObj));
        DateraObject.AppInstance appInstance = createAppInstance(conn, name, appInstanceEntity);

        //bring it online
        updateAppInstanceAdminState(conn, name, DateraObject.AppState.ONLINE);

        return appInstance;
    }

    public static DateraObject.Initiator createInitiator(DateraObject.DateraConnection conn, String name, String iqn) throws DateraObject.DateraError, UnsupportedEncodingException {

        HttpPost req = new HttpPost(generateApiUrl("initiators" ));

        DateraObject.Initiator initiator = new DateraObject.Initiator(name, iqn);
        StringEntity httpEntity = new StringEntity(gson.toJson(initiator));
        req.setEntity(httpEntity);

        return gson.fromJson(executeApiRequest(conn, req), DateraObject.Initiator.class);
    }

    public static DateraObject.Initiator getInitiator(DateraObject.DateraConnection conn, String iqn) throws DateraObject.DateraError {

        try {
            HttpGet getReq = new HttpGet(generateApiUrl("initiators", iqn));
            String response = executeApiRequest(conn, getReq);
            return gson.fromJson(response, DateraObject.Initiator.class);
        } catch (DateraObject.DateraError dateraError) {
            if (DateraObject.DateraErrorTypes.NotFoundError.equals(dateraError)) {
                return null;
            } else {
                throw dateraError;
            }
        }
    }

    public static void deleteInitiator(DateraObject.DateraConnection conn, String iqn) throws DateraObject.DateraError {

        HttpDelete req = new HttpDelete(generateApiUrl("initiators", iqn));
        executeApiRequest(conn, req);
    }

    public static DateraObject.InitiatorGroup createInitiatorGroup(DateraObject.DateraConnection conn, String name) throws UnsupportedEncodingException, DateraObject.DateraError {

        HttpPost createReq = new HttpPost(generateApiUrl("initiator_groups"));

        DateraObject.InitiatorGroup group = new DateraObject.InitiatorGroup(name, Collections.<String>emptyList());

        StringEntity httpEntity = new StringEntity(gson.toJson(group));
        createReq.setEntity(httpEntity);

        String response = executeApiRequest(conn, createReq);
        return gson.fromJson(response, DateraObject.InitiatorGroup.class);
    }

    public static void deleteInitatorGroup(DateraObject.DateraConnection conn, String name) throws DateraObject.DateraError {
        HttpDelete delReq = new HttpDelete(generateApiUrl("initiator_groups", name));
        executeApiRequest(conn, delReq);
    }

    public static DateraObject.InitiatorGroup getInitiatorGroup(DateraObject.DateraConnection conn, String name) throws DateraObject.DateraError {
        try {
            HttpGet getReq = new HttpGet(generateApiUrl("initiator_groups", name));
            String response = executeApiRequest(conn, getReq);
            return gson.fromJson(response, DateraObject.InitiatorGroup.class);

        } catch (DateraObject.DateraError dateraError) {
            if (DateraObject.DateraErrorTypes.NotFoundError.equals(dateraError)) {
                return null;
            } else {
                throw dateraError;
            }
        }
    }

    public static void updateInitiatorGroup(DateraObject.DateraConnection conn, String initiatorPath, String groupName, DateraObject.DateraOperation op) throws DateraObject.DateraError, UnsupportedEncodingException {

        DateraObject.InitiatorGroup initiatorGroup = getInitiatorGroup(conn, groupName);

        if (initiatorGroup == null) {
            throw new CloudRuntimeException("Unable to find initiator group by name " + groupName);
        }

        HttpPut addReq = new HttpPut(generateApiUrl("initiator_groups", groupName, "members"));

        DateraObject.Initiator initiator = new DateraObject.Initiator(initiatorPath, op);

        addReq.setEntity(new StringEntity(gson.toJson(initiator)));
        executeApiRequest(conn, addReq);
    }

    public static void addInitiatorToGroup(DateraObject.DateraConnection conn, String initiatorPath, String groupName) throws UnsupportedEncodingException, DateraObject.DateraError {
        updateInitiatorGroup(conn, initiatorPath, groupName, DateraObject.DateraOperation.ADD);
    }

    public void removeInitiatorFromGroup(DateraObject.DateraConnection conn, String initiatorPath, String groupName) throws DateraObject.DateraError, UnsupportedEncodingException {
        updateInitiatorGroup(conn, initiatorPath, groupName, DateraObject.DateraOperation.REMOVE);
    }

    public static Map<String, DateraObject.InitiatorGroup> getAppInstanceInitiatorGroups(DateraObject.DateraConnection conn, String appInstance) throws DateraObject.DateraError {
        HttpGet req = new HttpGet(generateApiUrl(
                "app_instances", appInstance,
                "storage_instances", DateraObject.DEFAULT_STORAGE_NAME,
                "acl_policy", "initiator_groups"));

        String response = executeApiRequest(conn, req);

        if (response == null) {
            return null;
        }

        Type responseType = new TypeToken<Map<String, DateraObject.InitiatorGroup>>() {}.getType();

        return gson.fromJson(response, responseType);
    }

    public static void assignGroupToAppInstance(DateraObject.DateraConnection conn, String group, String appInstance) throws DateraObject.DateraError, UnsupportedEncodingException {

        DateraObject.InitiatorGroup initiatorGroup = getInitiatorGroup(conn, group);

        if (initiatorGroup == null) {
            throw new CloudRuntimeException("Initator group " + group + " not found ");
        }

        Map<String, DateraObject.InitiatorGroup> initiatorGroups = getAppInstanceInitiatorGroups(conn, appInstance);

        if (initiatorGroups == null) {
            throw new CloudRuntimeException("Initator group not found for appInstnace " + appInstance);
        }

        for(DateraObject.InitiatorGroup ig : initiatorGroups.values()) {
           if (ig.getName().equals(group)) {
               //already assigned
               return;
           }
        }

        HttpPut url = new HttpPut(generateApiUrl(
                "app_instances", appInstance,
                "storage_instances", DateraObject.DEFAULT_STORAGE_NAME,
                "acl_policy", "initiator_groups"));

        url.setEntity(new StringEntity(
                gson.toJson(
                        new DateraObject.InitiatorGroup(
                                initiatorGroup.getPath(),
                                DateraObject.DateraOperation.ADD
                        )
                )
        ));

        executeApiRequest(conn, url);
    }

    public static void removeGroupFromAppInstance(DateraObject.DateraConnection conn, String group, String appInstance) throws DateraObject.DateraError, UnsupportedEncodingException {

        DateraObject.InitiatorGroup initiatorGroup = getInitiatorGroup(conn, group);

        if (initiatorGroup == null) {
            throw new CloudRuntimeException("Initator groups not found for appInstnace " + appInstance);
        }

        Map<String, DateraObject.InitiatorGroup> initiatorGroups = getAppInstanceInitiatorGroups(conn, appInstance);

        if (initiatorGroups == null) {
            throw new CloudRuntimeException("Initator group not found for appInstnace " + appInstance);
        }

        boolean groupAssigned = false;

        for(DateraObject.InitiatorGroup ig : initiatorGroups.values()) {
           if (ig.getName().equals(group)) {
               groupAssigned = true;
               break;
           }
        }

        if (!groupAssigned) {
            return;  // already removed
        }

        HttpPut url = new HttpPut(generateApiUrl(
                "app_instances", appInstance,
                "storage_instances", DateraObject.DEFAULT_STORAGE_NAME,
                "acl_policy", "initiator_groups"));

        url.setEntity(new StringEntity(
                gson.toJson(
                        new DateraObject.InitiatorGroup(
                                initiatorGroup.getPath(),
                                DateraObject.DateraOperation.REMOVE
                        )
                )
        ));

        executeApiRequest(conn, url);
    }

    public static void updateAppInstanceAdminState(DateraObject.DateraConnection conn, String appInstanceName, DateraObject.AppState appState) throws UnsupportedEncodingException, DateraObject.DateraError {

        DateraObject.AppInstance appInstance = new DateraObject.AppInstance(appState);
        HttpPut updateAppInstanceReq = new HttpPut(generateApiUrl("app_instances", appInstanceName));

        updateAppInstanceReq.setEntity(new StringEntity(gson.toJson(appInstance)));
        executeApiRequest(conn, updateAppInstanceReq);
    }

    public static void deleteAppInstance(DateraObject.DateraConnection conn, String name) throws UnsupportedEncodingException, DateraObject.DateraError {

        HttpDelete deleteAppInstanceReq = new HttpDelete(generateApiUrl("app_instances", name));
        updateAppInstanceAdminState(conn, name, DateraObject.AppState.OFFLINE);
        executeApiRequest(conn, deleteAppInstanceReq);
    }

    private static String executeApiRequest(DateraObject.DateraConnection conn, HttpRequest apiReq) throws DateraObject.DateraError {

        //Get the token first
        String authToken = null;
        try {
            authToken = login(conn);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable to login to Datera " + e.getMessage());
        }

        if (authToken == null){
            throw new CloudRuntimeException("Unable to login to Datera: error getting auth token ");
        }

        apiReq.addHeader(HEADER_AUTH_TOKEN, authToken);

        return executeHttp(conn, apiReq);
    }

    private static String executeHttp(DateraObject.DateraConnection conn, HttpRequest request) throws DateraObject.DateraError {
        DefaultHttpClient httpclient = new DefaultHttpClient();
        //CloseableHttpClient httpclient = HttpClientBuilder.create().build();
        String response = null;

        if (null == httpclient) {
            throw new CloudRuntimeException("Unable to create httpClient for request");
        }

        try {

            request.setHeader(HEADER_CONTENT_TYPE, HEADER_VALUE_JSON);

            HttpHost target = new HttpHost(conn.getManagementIp(), conn.getManagementPort(), SCHEME_HTTP);

            HttpResponse httpResponse = httpclient.execute(target, request);

            HttpEntity entity = httpResponse.getEntity();
            StatusLine status = httpResponse.getStatusLine();
            response = EntityUtils.toString(entity);

            assert response != null;

            if (status.getStatusCode() != HttpStatus.SC_OK) {
                // check if this is an error
                DateraObject.DateraError error = gson.fromJson(response, DateraObject.DateraError.class);
                if (error != null && error.isError()) {
                    throw error;
                } else {
                    throw new CloudRuntimeException("Error while trying to get HTTP object from Datera");
                }

            }

        } catch (IOException e) {
            throw new CloudRuntimeException("Error while sending request to Datera. Error " + e.getMessage());
        }

        return response;
    }

    protected static String generateApiUrl(String... args) {
        ArrayList<String> urlList = new ArrayList<String>(Arrays.asList(args));

        urlList.add(0, API_VERSION);
        urlList.add(0, "");

        return StringUtils.join(urlList, "/");
    }

    public static String getManagementVip(String url) {
        return getVip(DateraUtil.MANAGEMENT_VIP, url);
    }

    public static String getStorageVip(String url) {
        return getVip(DateraUtil.STORAGE_VIP, url);
    }

    public static int getManagementPort(String url) {
        return getPort(DateraUtil.MANAGEMENT_VIP, url, DEFAULT_MANAGEMENT_PORT);
    }

    public static int getStoragePort(String url) {
        return getPort(DateraUtil.STORAGE_VIP, url, DEFAULT_STORAGE_PORT);
    }

    public static int getNumReplicas(String url) {
        try {

            String value = getValue(DateraUtil.NUM_REPLICAS, url, false);
            return Integer.parseInt(value);

        }catch (NumberFormatException ex){
            return DEFAULT_NUM_REPLICAS;
        }

    }

    public static String getVolPlacement(String url) {
        String volPlacement = getValue(DateraUtil.VOL_PLACEMENT, url, false);
        if (volPlacement == null) {
            return DEFAULT_VOL_PLACEMENT;
        } else {
            return volPlacement;
        }
    }

    private static String getVip(String keyToMatch, String url) {
        String delimiter = ":";

        String storageVip = getValue(keyToMatch, url);

        int index = storageVip.indexOf(delimiter);

        if (index != -1) {
            return storageVip.substring(0, index);
        }

        return storageVip;
    }

    private static int getPort(String keyToMatch, String url, int defaultPortNumber) {
        String delimiter = ":";

        String storageVip = getValue(keyToMatch, url);

        int index = storageVip.indexOf(delimiter);

        int portNumber = defaultPortNumber;

        if (index != -1) {
            String port = storageVip.substring(index + delimiter.length());

            try {
                portNumber = Integer.parseInt(port);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid URL format (port is not an integer)");
            }
        }

        return portNumber;
    }

    public static String getValue(String keyToMatch, String url) {
        return getValue(keyToMatch, url, true);
    }

    public static String getValue(String keyToMatch, String url, boolean throwExceptionIfNotFound) {
        String delimiter1 = ";";
        String delimiter2 = "=";

        StringTokenizer st = new StringTokenizer(url, delimiter1);

        while (st.hasMoreElements()) {
            String token = st.nextElement().toString();

            int index = token.indexOf(delimiter2);

            if (index == -1) {
                throw new CloudRuntimeException("Invalid URL format");
            }

            String key = token.substring(0, index);

            if (key.equalsIgnoreCase(keyToMatch)) {
                return token.substring(index + delimiter2.length());
            }
        }

        if (throwExceptionIfNotFound) {
            throw new CloudRuntimeException("Key not found in URL");
        }

        return null;
    }

    public static String getModifiedUrl(String originalUrl) {
        StringBuilder sb = new StringBuilder();

        String delimiter = ";";

        StringTokenizer st = new StringTokenizer(originalUrl, delimiter);

        while (st.hasMoreElements()) {
            String token = st.nextElement().toString().toUpperCase();

            if (token.startsWith(DateraUtil.MANAGEMENT_VIP.toUpperCase()) || token.startsWith(DateraUtil.STORAGE_VIP.toUpperCase())) {
                sb.append(token).append(delimiter);
            }
        }

        String modifiedUrl = sb.toString();
        int lastIndexOf = modifiedUrl.lastIndexOf(delimiter);

        if (lastIndexOf == (modifiedUrl.length() - delimiter.length())) {
            return modifiedUrl.substring(0, lastIndexOf);
        }

        return modifiedUrl;
    }

    public static DateraObject.DateraConnection getDateraConnection(long storagePoolId, StoragePoolDetailsDao storagePoolDetailsDao) {
        StoragePoolDetailVO storagePoolDetail = storagePoolDetailsDao.findDetail(storagePoolId, DateraUtil.MANAGEMENT_VIP);

        String mVip = storagePoolDetail.getValue();

        storagePoolDetail = storagePoolDetailsDao.findDetail(storagePoolId, DateraUtil.MANAGEMENT_PORT);

        int mPort = Integer.parseInt(storagePoolDetail.getValue());

        storagePoolDetail = storagePoolDetailsDao.findDetail(storagePoolId, DateraUtil.CLUSTER_ADMIN_USERNAME);

        String clusterAdminUsername = storagePoolDetail.getValue();

        storagePoolDetail = storagePoolDetailsDao.findDetail(storagePoolId, DateraUtil.CLUSTER_ADMIN_PASSWORD);

        String clusterAdminPassword = storagePoolDetail.getValue();

        return new DateraObject.DateraConnection(mVip, mPort, clusterAdminUsername, clusterAdminPassword) ;
    }

    public static boolean isInitiatorPresentInGroup(DateraObject.Initiator initiator, DateraObject.InitiatorGroup initiatorGroup) {

        for (String memberPath : initiatorGroup.getMembers() ) {
            if (memberPath.equals(initiator.getPath())) {
                return true;
            }
        }

        return false;
    }

    /*public static boolean hostsSupport_iScsi(List<HostVO> hosts) {
        if (hosts == null || hosts.size() == 0) {
            return false;
        }

        for (Host host : hosts) {
            if (!hostSupport_iScsi(host)){
                return false;
            }
        }

        return true;
    }

    public static boolean hostSupport_iScsi(Host host) {
        if (host == null || host.getStorageUrl() == null || host.getStorageUrl().trim().length() == 0 || !host.getStorageUrl().startsWith("iqn")) {
            return false;
        }
        return true;
    }*/


    public static String getInitiatorGroupKey(long storagePoolId) {
        return "DateraInitiatorGroup-" + storagePoolId;
    }

    public static int bytesToGb(long volumeSizeBytes) {
        return (int) Math.ceil(volumeSizeBytes/1073741824.0);
    }

    public static long gbToBytes(int volumeSizeGb) {
        return volumeSizeGb*1024*1024;
    }

    /**
     * IQN path is stored in the DB by cloudstack it is of the form /<IQN>/0
     *
     * @param iqn: IQN of the LUN
     * @return IQN path as defined above
     */
    public static String generateIqnPath(String iqn) {
        if (iqn != null) {
            return "/" + iqn + "/0";
        }
        return null;
    }

    /**
     * Does the opposite of generateIqnPath
     *
     * @param iqnPath
     * @return timmed IQN path
     */

    public static String extractIqn(String iqnPath) {

        if (iqnPath == null) {
            return null;
        }

        if (iqnPath.endsWith("/")) {
            iqnPath = iqnPath.substring(0, iqnPath.length() - 1);
        }

        final String tokens[] = iqnPath.split("/");
        if (tokens.length != 3) {
            final String msg = "Wrong iscsi path " + iqnPath + " it should be /targetIQN/LUN";
            s_logger.warn(msg);
            return null;
        }

        return tokens[1].trim();
    }


}
