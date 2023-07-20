/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.authorization.solr.authorizer;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.security.auth.login.Configuration;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.plugin.audit.RangerMultiResourceAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.apache.solr.security.AuthorizationContext.RequestType;
import org.apache.solr.security.AuthorizationPlugin;
import org.apache.solr.security.AuthorizationResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.AuthorizationContext.CollectionRequest;

public class RangerSolrAuthorizer implements AuthorizationPlugin {
	private static final Log logger = LogFactory
			.getLog(RangerSolrAuthorizer.class);

	public static final String PROP_USE_PROXY_IP = "xasecure.solr.use_proxy_ip";
	public static final String PROP_PROXY_IP_HEADER = "xasecure.solr.proxy_ip_header";
	public static final String PROP_SOLR_APP_NAME = "xasecure.solr.app.name";

	public static final String KEY_COLLECTION = "collection";

	public static final String ACCESS_TYPE_CREATE = "create";
	public static final String ACCESS_TYPE_UPDATE = "update";
	public static final String ACCESS_TYPE_QUERY = "query";
	public static final String ACCESS_TYPE_OTHERS = "others";
	public static final String ACCESS_TYPE_ADMIN = "solr_admin";

	private static volatile RangerBasePlugin solrPlugin = null;

	boolean useProxyIP = false;
	String proxyIPHeader = "HTTP_X_FORWARDED_FOR";
	String solrAppName = "Client";

	public RangerSolrAuthorizer() {
		logger.info("RangerSolrAuthorizer()");

	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.apache.solr.security.SolrAuthorizationPlugin#init(java.util.Map)
	 */
	@Override
	public void init(Map<String, Object> initInfo) {
		logger.info("init()");

		try {
			useProxyIP = RangerConfiguration.getInstance().getBoolean(
					PROP_USE_PROXY_IP, useProxyIP);
			proxyIPHeader = RangerConfiguration.getInstance().get(
					PROP_PROXY_IP_HEADER, proxyIPHeader);
			// First get from the -D property
			solrAppName = System.getProperty("solr.kerberos.jaas.appname",
					solrAppName);
			// Override if required from Ranger properties
			solrAppName = RangerConfiguration.getInstance().get(
					PROP_SOLR_APP_NAME, solrAppName);

			logger.info("init(): useProxyIP=" + useProxyIP);
			logger.info("init(): proxyIPHeader=" + proxyIPHeader);
			logger.info("init(): solrAppName=" + solrAppName);
			logger.info("init(): KerberosName.rules="
					+ MiscUtil.getKerberosNamesRules());
			authToJAASFile();

		} catch (Throwable t) {
			logger.fatal("Error init", t);
		}

		try {
			RangerBasePlugin me = solrPlugin;
			if (me == null) {
				synchronized(RangerSolrAuthorizer.class) {
					me = solrPlugin;
					logger.info("RangerSolrAuthorizer(): init called");
					if (me == null) {
						me = solrPlugin = new RangerBasePlugin("solr", "solr");
					}
				}
			}
			solrPlugin.init();
		} catch (Throwable t) {
			logger.fatal("Error creating and initializing RangerBasePlugin()");
		}
	}

	private void authToJAASFile() {
		try {
			// logger.info("DEFAULT UGI=" +
			// UserGroupInformation.getLoginUser());

			Configuration config = Configuration.getConfiguration();
			MiscUtil.authWithConfig(solrAppName, config);
			logger.info("POST AUTH UGI=" + UserGroupInformation.getLoginUser());
		} catch (Throwable t) {
			logger.error("Error authenticating for appName=" + solrAppName, t);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.io.Closeable#close()
	 */
	@Override
	public void close() throws IOException {
		logger.info("close() called");
		try {
			solrPlugin.cleanup();
		} catch (Throwable t) {
			logger.error("Error cleaning up Ranger plugin. Ignoring error", t);
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.apache.solr.security.SolrAuthorizationPlugin#authorize(org.apache
	 * .solr.security.SolrRequestContext)
	 */
	@Override
	public AuthorizationResponse authorize(AuthorizationContext context) {
		boolean isDenied = true;

		try {
			if (logger.isDebugEnabled()) {
				logAuthorizationConext(context);
			}

			RangerMultiResourceAuditHandler auditHandler = new RangerMultiResourceAuditHandler();

			String userName = getUserName(context);
			Set<String> userGroups = getGroupsForUser(userName);
			String ip = null;
			Date eventTime = new Date();

			// // Set the IP
			if (useProxyIP) {
				ip = context.getHttpHeader(proxyIPHeader);
			}
			if (ip == null) {
				ip = context.getHttpHeader("REMOTE_ADDR");
			}

			// Create the list of requests for access check. Each field is
			// broken
			// into a request
			List<RangerAccessRequestImpl> rangerRequests = new ArrayList<RangerAccessRequestImpl>();
			for (CollectionRequest collectionRequest : context
					.getCollectionRequests()) {

				RangerAccessRequestImpl requestForCollection = createRequest(
						userName, userGroups, ip, eventTime, context,
						collectionRequest);
				if (requestForCollection != null) {
					rangerRequests.add(requestForCollection);
				}
			}
			if (logger.isDebugEnabled()) {
				logger.debug("rangerRequests.size()=" + rangerRequests.size());
			}
			try {
				// Let's check the access for each request/resource
				for (RangerAccessRequestImpl rangerRequest : rangerRequests) {
					RangerAccessResult result = solrPlugin.isAccessAllowed(
							rangerRequest, auditHandler);
					if (logger.isDebugEnabled()) {
						logger.debug("rangerRequest=" + result);
					}
					if (result == null || !result.getIsAllowed()) {
						isDenied = true;
						// rejecting on first failure
						break;
					} else {
						isDenied = false;
					}
				}
			} finally {
				auditHandler.flushAudit();
			}
		} catch (Throwable t) {
			MiscUtil.logErrorMessageByInterval(logger, t.getMessage(), t);
		}
		AuthorizationResponse response = null;
		if (isDenied) {
			response = new AuthorizationResponse(403);
		} else {
			response = new AuthorizationResponse(200);
		}
		if (logger.isDebugEnabled()) {
			logger.debug("context=" + context + ": returning: " + isDenied);
		}
		return response;
	}

	/**
	 * @param context
	 */
	private void logAuthorizationConext(AuthorizationContext context) {
		try {
			// Note: This method should be called with isDebugEnabled() or
			// isInfoEnabled() scope

			String collections = "";
			int i = -1;
			for (CollectionRequest collectionRequest : context
					.getCollectionRequests()) {
				i++;
				if (i > 0) {
					collections += ",";
				}
				collections += collectionRequest.collectionName;
			}

			String headers = "";
			i = -1;
			@SuppressWarnings("unchecked")
			Enumeration<String> eList = context.getHeaderNames();
			while (eList.hasMoreElements()) {
				i++;
				if (i > 0) {
					headers += ",";
				}
				String header = eList.nextElement();
				String value = context.getHttpHeader(header);
				headers += header + "=" + value;
			}

			String ipAddress = context.getHttpHeader("HTTP_X_FORWARDED_FOR");

			if (ipAddress == null) {
				ipAddress = context.getHttpHeader("REMOTE_HOST");
			}
			if (ipAddress == null) {
				ipAddress = context.getHttpHeader("REMOTE_ADDR");
			}

			String userName = getUserName(context);
			Set<String> groups = getGroupsForUser(userName);

			logger.info("AuthorizationContext: context.getResource()="
					+ context.getResource() + ", solarParams="
					+ context.getParams() + ", requestType="
					+ context.getRequestType() + ", ranger.requestType="
					+ mapToRangerAccessType(context) + ", userPrincipal="
					+ context.getUserPrincipal() + ", userName=" + userName
					+ ", groups=" + groups + ", ipAddress=" + ipAddress
					+ ", collections=" + collections + ", headers=" + headers);
		} catch (Throwable t) {
			logger.error("Error getting request context!!!", t);
		}

	}

	/**
	 * @param userName
	 * @param userGroups
	 * @param ip
	 * @param eventTime
	 * @param context
	 * @param collectionRequest
	 * @return
	 */
	private RangerAccessRequestImpl createRequest(String userName,
			Set<String> userGroups, String ip, Date eventTime,
			AuthorizationContext context, CollectionRequest collectionRequest) {

		String accessType = mapToRangerAccessType(context);
		String action = accessType;

		if (collectionRequest.collectionName != null) {
			RangerAccessRequestImpl rangerRequest = createBaseRequest(userName,
					userGroups, ip, eventTime);
			RangerAccessResourceImpl rangerResource = new RangerAccessResourceImpl();
			rangerResource.setValue(KEY_COLLECTION,
					collectionRequest.collectionName);
			rangerRequest.setResource(rangerResource);
			rangerRequest.setAccessType(accessType);
			rangerRequest.setAction(action);

			return rangerRequest;
		}
		
		logger.fatal("Can't create RangerRequest oject. userName="
				+ userName + ", accessType=" + accessType + ", ip=" + ip
				+ ", collectionRequest=" + collectionRequest);

		return null;
	}

	private RangerAccessRequestImpl createBaseRequest(String userName,
			Set<String> userGroups, String ip, Date eventTime) {
		RangerAccessRequestImpl rangerRequest = new RangerAccessRequestImpl();
		if (userName != null && !userName.isEmpty()) {
			rangerRequest.setUser(userName);
		}
		if (userGroups != null && userGroups.size() > 0) {
			rangerRequest.setUserGroups(userGroups);
		}
		if (ip != null && !ip.isEmpty()) {
			rangerRequest.setClientIPAddress(ip);
		}
		rangerRequest.setAccessTime(eventTime);
		return rangerRequest;
	}

	private String getUserName(AuthorizationContext context) {
		Principal principal = context.getUserPrincipal();
		if (principal != null) {
			return MiscUtil.getShortNameFromPrincipalName(principal.getName());
		}
		return null;
	}

	/**
	 * @param name
	 * @return
	 */
	private Set<String> getGroupsForUser(String name) {
		return MiscUtil.getGroupsForRequestUser(name);
	}

	String mapToRangerAccessType(AuthorizationContext context) {
		String accessType = ACCESS_TYPE_OTHERS;

		RequestType requestType = context.getRequestType();
		if (RequestType.ADMIN.equals(requestType)) {
			accessType = ACCESS_TYPE_ADMIN;
		} else if (RequestType.READ.equals(requestType)) {
			accessType = ACCESS_TYPE_QUERY;
		} else if (RequestType.WRITE.equals(requestType)) {
			accessType = ACCESS_TYPE_UPDATE;
		} else if (RequestType.UNKNOWN.equals(requestType)) {
			logger.info("UNKNOWN request type. Mapping it to " + accessType
					+ ". Resource=" + context.getResource());
			accessType = ACCESS_TYPE_OTHERS;
		} else {
			logger.info("Request type is not supported. requestType="
					+ requestType + ". Mapping it to " + accessType
					+ ". Resource=" + context.getResource());
		}
		return accessType;
	}

}
