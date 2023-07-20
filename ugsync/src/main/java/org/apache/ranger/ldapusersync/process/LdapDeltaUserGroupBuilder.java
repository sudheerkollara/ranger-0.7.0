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

 package org.apache.ranger.ldapusersync.process;


import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import javax.naming.Context;
import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;

import org.apache.log4j.Logger;
import org.apache.ranger.unixusersync.config.UserGroupSyncConfig;
import org.apache.ranger.usergroupsync.AbstractUserGroupSource;
import org.apache.ranger.usergroupsync.UserGroupSink;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;

public class LdapDeltaUserGroupBuilder extends AbstractUserGroupSource {
	
	private static final Logger LOG = Logger.getLogger(LdapDeltaUserGroupBuilder.class);
	
	private static final int PAGE_SIZE = 500;
	private static long deltaSyncUserTime = 0; // Used for AD uSNChanged 
	private static long deltaSyncGroupTime = 0; // Used for AD uSNChanged
	private String deltaSyncUserTimeStamp; // Used for OpenLdap modifyTimestamp
	private String deltaSyncGroupTimeStamp; // Used for OpenLdap modifyTimestamp

  private String ldapUrl;
  private String ldapBindDn;
  private String ldapBindPassword;
  private String ldapAuthenticationMechanism;
  private String ldapReferral;
  private String searchBase;

  private String[] userSearchBase;
	private String userNameAttribute;
  private int    userSearchScope;
  private String userObjectClass;
  private String userSearchFilter;
  private String extendedUserSearchFilter;
  private SearchControls userSearchControls;
  private Set<String> userGroupNameAttributeSet;

  private boolean pagedResultsEnabled = true;
  private int pagedResultsSize = 500;

  private boolean groupSearchFirstEnabled = false;
  private boolean userSearchEnabled = false;
  private boolean groupSearchEnabled = true;
  private String[] groupSearchBase;
  private int    groupSearchScope;
  private String groupObjectClass;
  private String groupSearchFilter;
  private String extendedGroupSearchFilter;
  private String extendedAllGroupsSearchFilter;
  private SearchControls groupSearchControls;
  private String groupMemberAttributeName;
  private String groupNameAttribute;

	private LdapContext ldapContext;
	StartTlsResponse tls;

	private boolean userNameCaseConversionFlag = false;
	private boolean groupNameCaseConversionFlag = false;
	private boolean userNameLowerCaseFlag = false;
	private boolean groupNameLowerCaseFlag = false;

  private boolean  groupUserMapSyncEnabled = false;

  //private Map<String, UserInfo> userGroupMap;
  
  private Table<String, String, String> groupUserTable; 

	public static void main(String[] args) throws Throwable {
		LdapDeltaUserGroupBuilder  ugBuilder = new LdapDeltaUserGroupBuilder();
		ugBuilder.init();
	}
	
	public LdapDeltaUserGroupBuilder() {
		super();
		LOG.info("LdapDeltaUserGroupBuilder created");
		
		String userNameCaseConversion = config.getUserNameCaseConversion();
		
		if (UserGroupSyncConfig.UGSYNC_NONE_CASE_CONVERSION_VALUE.equalsIgnoreCase(userNameCaseConversion)) {
		    userNameCaseConversionFlag = false;
		}
		else {
		    userNameCaseConversionFlag = true;
		    userNameLowerCaseFlag = UserGroupSyncConfig.UGSYNC_LOWER_CASE_CONVERSION_VALUE.equalsIgnoreCase(userNameCaseConversion);
		}
		
		String groupNameCaseConversion = config.getGroupNameCaseConversion();
		
		if (UserGroupSyncConfig.UGSYNC_NONE_CASE_CONVERSION_VALUE.equalsIgnoreCase(groupNameCaseConversion)) {
		    groupNameCaseConversionFlag = false;
		}
		else {
		    groupNameCaseConversionFlag = true;
		    groupNameLowerCaseFlag = UserGroupSyncConfig.UGSYNC_LOWER_CASE_CONVERSION_VALUE.equalsIgnoreCase(groupNameCaseConversion);
		}
	}

	@Override
	public void init() throws Throwable{		
		deltaSyncUserTime = 0;
		deltaSyncGroupTime = 0;
		DateFormat dateFormat = new SimpleDateFormat("yyyyMMddhhmmss");
		deltaSyncUserTimeStamp = dateFormat.format(new Date(0));
		deltaSyncGroupTimeStamp = dateFormat.format(new Date(0));
		setConfig();
	}
	
	private void createLdapContext() throws Throwable {
		Properties env = new Properties();
		env.put(Context.INITIAL_CONTEXT_FACTORY,
				"com.sun.jndi.ldap.LdapCtxFactory");
		env.put(Context.PROVIDER_URL, ldapUrl);
		if (ldapUrl.startsWith("ldaps") && (config.getSSLTrustStorePath() != null && !config.getSSLTrustStorePath().trim().isEmpty())) {
			env.put("java.naming.ldap.factory.socket", "org.apache.ranger.ldapusersync.process.CustomSSLSocketFactory");
		}

		ldapContext = new InitialLdapContext(env, null);
		if (!ldapUrl.startsWith("ldaps")) {
			if (config.isStartTlsEnabled()) {
				tls = (StartTlsResponse) ldapContext.extendedOperation(new StartTlsRequest());
				if (config.getSSLTrustStorePath() != null && !config.getSSLTrustStorePath().trim().isEmpty()) {
					tls.negotiate(CustomSSLSocketFactory.getDefault());
				} else {
					tls.negotiate();
				}
				LOG.info("Starting TLS session...");
			}
		}

		ldapContext.addToEnvironment(Context.SECURITY_PRINCIPAL, ldapBindDn);
		ldapContext.addToEnvironment(Context.SECURITY_CREDENTIALS, ldapBindPassword);
		ldapContext.addToEnvironment(Context.SECURITY_AUTHENTICATION, ldapAuthenticationMechanism);
		ldapContext.addToEnvironment(Context.REFERRAL, ldapReferral);
	}
	
	private void setConfig() throws Throwable {
		LOG.info("LdapDeltaUserGroupBuilder initialization started");

		groupSearchFirstEnabled =   config.isGroupSearchFirstEnabled();
		userSearchEnabled =   config.isUserSearchEnabled();
		groupSearchEnabled =   config.isGroupSearchEnabled();
    ldapUrl = config.getLdapUrl();
    ldapBindDn = config.getLdapBindDn();
    ldapBindPassword = config.getLdapBindPassword();
    //ldapBindPassword = "admin-password";
    ldapAuthenticationMechanism = config.getLdapAuthenticationMechanism();
    ldapReferral = config.getContextReferral();
		searchBase = config.getSearchBase();
		
		userSearchBase = config.getUserSearchBase().split(";");
		userSearchScope = config.getUserSearchScope();
		userObjectClass = config.getUserObjectClass();
		userSearchFilter = config.getUserSearchFilter();
		
		userNameAttribute = config.getUserNameAttribute();
		
		Set<String> userSearchAttributes = new HashSet<String>();
		userSearchAttributes.add(userNameAttribute);
		// For Group based search, user's group name attribute should not be added to the user search attributes
		if (!groupSearchFirstEnabled && !groupSearchEnabled) {
			userGroupNameAttributeSet = config.getUserGroupNameAttributeSet();
			for (String useGroupNameAttribute : userGroupNameAttributeSet) {
				userSearchAttributes.add(useGroupNameAttribute);
			}
		}
		userSearchAttributes.add("uSNChanged");
		userSearchAttributes.add("modifytimestamp");
		userSearchControls = new SearchControls();
		userSearchControls.setSearchScope(userSearchScope);
		userSearchControls.setReturningAttributes(userSearchAttributes.toArray(
				new String[userSearchAttributes.size()]));

    pagedResultsEnabled =   config.isPagedResultsEnabled();
    pagedResultsSize =   config.getPagedResultsSize();

    groupSearchBase = config.getGroupSearchBase().split(";");
    groupSearchScope = config.getGroupSearchScope();
    groupObjectClass = config.getGroupObjectClass();
    groupSearchFilter = config.getGroupSearchFilter();
    groupMemberAttributeName =  config.getUserGroupMemberAttributeName();
    groupNameAttribute = config.getGroupNameAttribute();

    extendedGroupSearchFilter =  "(&"  + extendedGroupSearchFilter + "(|(" + groupMemberAttributeName + "={0})(" + groupMemberAttributeName + "={1})))";
    groupUserMapSyncEnabled = config.isGroupUserMapSyncEnabled();

    groupSearchControls = new SearchControls();
    groupSearchControls.setSearchScope(groupSearchScope);

    Set<String> groupSearchAttributes = new HashSet<String>();
    groupSearchAttributes.add(groupNameAttribute);
    groupSearchAttributes.add(groupMemberAttributeName);
    groupSearchAttributes.add("uSNChanged");
    groupSearchAttributes.add("modifytimestamp");
    groupSearchControls.setReturningAttributes(groupSearchAttributes.toArray(
			new String[groupSearchAttributes.size()]));

		if (LOG.isInfoEnabled()) {
			LOG.info("LdapDeltaUserGroupBuilder initialization completed with --  "
					+ "ldapUrl: " + ldapUrl
					+ ",  ldapBindDn: " + ldapBindDn
					+ ",  ldapBindPassword: ***** "
					+ ",  ldapAuthenticationMechanism: " + ldapAuthenticationMechanism
          + ",  searchBase: " + searchBase
          + ",  userSearchBase: " + Arrays.toString(userSearchBase)
          + ",  userSearchScope: " + userSearchScope
					+ ",  userObjectClass: " + userObjectClass
					+ ",  userSearchFilter: " + userSearchFilter
					+ ",  extendedUserSearchFilter: " + extendedUserSearchFilter
					+ ",  userNameAttribute: " + userNameAttribute
					+ ",  userSearchAttributes: " + userSearchAttributes
          + ",  userGroupNameAttributeSet: " + userGroupNameAttributeSet
          + ",  pagedResultsEnabled: " + pagedResultsEnabled
          + ",  pagedResultsSize: " + pagedResultsSize
          + ",  groupSearchEnabled: " + groupSearchEnabled
          + ",  groupSearchBase: " + Arrays.toString(groupSearchBase)
          + ",  groupSearchScope: " + groupSearchScope
          + ",  groupObjectClass: " + groupObjectClass
          + ",  groupSearchFilter: " + groupSearchFilter
          + ",  extendedGroupSearchFilter: " + extendedGroupSearchFilter
          + ",  extendedAllGroupsSearchFilter: " + extendedAllGroupsSearchFilter
          + ",  groupMemberAttributeName: " + groupMemberAttributeName
          + ",  groupNameAttribute: " + groupNameAttribute
          + ", groupSearchAttributes: " + groupSearchAttributes
          + ",  groupUserMapSyncEnabled: " + groupUserMapSyncEnabled
          + ", groupSearchFirstEnabled: " + groupSearchFirstEnabled
          + ", userSearchEnabled: " + userSearchEnabled
          + ",  ldapReferral: " + ldapReferral
      );
		}

	}
	
	private void closeLdapContext() throws Throwable {
		if (tls != null) {
			tls.close();
		}
		if (ldapContext != null) {
			ldapContext.close();
		}
	}
	
	@Override
	public boolean isChanged() {
		// we do not want to get the full ldap dit and check whether anything has changed
		return true;
	}

	@Override
	public void updateSink(UserGroupSink sink) throws Throwable {
		LOG.info("LdapDeltaUserGroupBuilder updateSink started");
		//userGroupMap = new HashMap<String, UserInfo>();
		groupUserTable = HashBasedTable.create(); 
		if (!groupSearchFirstEnabled) {
			LOG.info("Performing user search first");
			getUsers(sink);
			if (groupSearchEnabled) {
				getGroups(sink);
			}
			//LOG.debug("Total No. of users saved = " + groupUserTable.columnKeySet().size());
			
		} else {
			LOG.info("Performing Group search first");
			getGroups(sink);
			if (userSearchEnabled) {
				LOG.info("User search is enabled and hence computing user membership.");
				getUsers(sink);
			} 
		}
		if (groupUserTable.isEmpty()) {
			//System.out.println("groupUserTable is empty!!");
			return;
		}
		Iterator<String> groupUserTableIterator = groupUserTable.rowKeySet().iterator();
		while (groupUserTableIterator.hasNext()) {
			String groupName = groupUserTableIterator.next();
			//System.out.println("Group name from the groupUserTable: " + groupName);
			Map<String,String> groupUsersMap =  groupUserTable.row(groupName);
			Set<String> userSet = new HashSet<String>();
			for(Map.Entry<String, String> entry : groupUsersMap.entrySet()){
				String transformUserName = userNameTransform(entry.getKey()); 
		         userSet.add(transformUserName);
		    }
			List<String> userList = new ArrayList<>(userSet);
			String transformGroupName = groupNameTransform(groupName);
			try { 
				sink.addOrUpdateGroup(transformGroupName, userList);
			} catch (Throwable t) {
				LOG.error("sink.addOrUpdateGroup failed with exception: " + t.getMessage()
				+ ", for group: " + transformGroupName
				+ ", users: " + userList);
			}
		}
	}
	
	private void getUsers(UserGroupSink sink) throws Throwable {
		NamingEnumeration<SearchResult> userSearchResultEnum = null;
		NamingEnumeration<SearchResult> groupSearchResultEnum = null;
		try {
			createLdapContext();
			int total;
			// Activate paged results
			if (pagedResultsEnabled)   {
				ldapContext.setRequestControls(new Control[]{
						new PagedResultsControl(pagedResultsSize, Control.NONCRITICAL) });
			}
			DateFormat dateFormat = new SimpleDateFormat("yyyyMMddhhmmss");
			extendedUserSearchFilter = "(objectclass=" + userObjectClass + ")(|(uSNChanged>=" + deltaSyncUserTime + ")(modifyTimestamp>=" + deltaSyncUserTimeStamp + "Z))";
			
			if (userSearchFilter != null && !userSearchFilter.trim().isEmpty()) {
				String customFilter = userSearchFilter.trim();
				if (!customFilter.startsWith("(")) {
					customFilter = "(" + customFilter + ")";
				}

				extendedUserSearchFilter = "(&" + extendedUserSearchFilter + customFilter + ")";
			} else {
				extendedUserSearchFilter = "(&" + extendedUserSearchFilter + ")";
			}
			LOG.info("extendedUserSearchFilter = " + extendedUserSearchFilter);

			long highestdeltaSyncUserTime = deltaSyncUserTime;

			// When multiple OUs are configured, go through each OU as the user search base to search for users.
			for (int ou=0; ou<userSearchBase.length; ou++) {
				byte[] cookie = null;
				int counter = 0;
				try {
				do {
					userSearchResultEnum = ldapContext
							.search(userSearchBase[ou], extendedUserSearchFilter,
									userSearchControls);
					
					while (userSearchResultEnum.hasMore()) {
						// searchResults contains all the user entries
						final SearchResult userEntry = userSearchResultEnum.next();

						if (userEntry == null)  {
							if (LOG.isInfoEnabled())  {
								LOG.info("userEntry null, skipping sync for the entry");
							}
							continue;
						}

						Attributes attributes =   userEntry.getAttributes();
						if (attributes == null)  {
							if (LOG.isInfoEnabled())  {
								LOG.info("attributes  missing for entry " + userEntry.getNameInNamespace() +
										", skipping sync");
							}
							continue;
						}

						Attribute userNameAttr  = attributes.get(userNameAttribute);
						if (userNameAttr == null)  {
							if (LOG.isInfoEnabled())  {
								LOG.info(userNameAttribute + " missing for entry " + userEntry.getNameInNamespace() +
										", skipping sync");
							}
							continue;
						}

						String userName = (String) userNameAttr.get();

						if (userName == null || userName.trim().isEmpty())  {
							if (LOG.isInfoEnabled())  {
								LOG.info(userNameAttribute + " empty for entry " + userEntry.getNameInNamespace() +
										", skipping sync");
							}
							continue;
						}
						
						Attribute timeStampAttr  = attributes.get("uSNChanged");
						if (timeStampAttr != null) {
							String uSNChangedVal = (String) timeStampAttr.get();
							long currentDeltaSyncTime = Long.parseLong(uSNChangedVal);
							LOG.info("uSNChangedVal = " + uSNChangedVal + "and currentDeltaSyncTime = " + currentDeltaSyncTime);
							if (currentDeltaSyncTime > highestdeltaSyncUserTime) {
								highestdeltaSyncUserTime = currentDeltaSyncTime;
							}
						} else {
							timeStampAttr = attributes.get("modifytimestamp");
							if (timeStampAttr != null) {
								String timeStampVal = (String) timeStampAttr.get();
								Date parseDate = dateFormat.parse(timeStampVal);						
								long currentDeltaSyncTime = parseDate.getTime();
								LOG.info("timeStampVal = " + timeStampVal + "and currentDeltaSyncTime = " + currentDeltaSyncTime);
								if (currentDeltaSyncTime > highestdeltaSyncUserTime) {
									highestdeltaSyncUserTime = currentDeltaSyncTime;
									deltaSyncUserTimeStamp = timeStampVal;
								}
							}
						}

						if (!groupSearchFirstEnabled) {
							String transformUserName = userNameTransform(userName);
							try {
								sink.addOrUpdateUser(transformUserName);
							} catch (Throwable t) {
								LOG.error("sink.addOrUpdateUser failed with exception: " + t.getMessage()
								+ ", for user: " + transformUserName);
							}
							Set<String> groups = new HashSet<String>();

							// Get all the groups from the group name attribute of the user only when group search is not enabled.
							if (!groupSearchEnabled) {
								for (String useGroupNameAttribute : userGroupNameAttributeSet) {
									Attribute userGroupfAttribute = userEntry.getAttributes().get(useGroupNameAttribute);
									if (userGroupfAttribute != null) {
										NamingEnumeration<?> groupEnum = userGroupfAttribute.getAll();
										while (groupEnum.hasMore()) {
											String gName = getShortGroupName((String) groupEnum
													.next());
											String transformGroupName = groupNameTransform(gName);
											groups.add(transformGroupName);
										}
									}
								}
							}

							List<String> groupList = new ArrayList<String>(groups);
							try {
								sink.addOrUpdateUser(transformUserName, groupList);
							} catch (Throwable t) {
								LOG.error("sink.addOrUpdateUserGroups failed with exception: " + t.getMessage()
								+ ", for user: " + transformUserName + " and groups: " + groupList);
							}
							counter++;
							if (counter <= 2000) {
								if (LOG.isInfoEnabled()) {
									LOG.info("Updating user count: " + counter
											+ ", userName: " + userName + ", groupList: "
											+ groupList);
								}
								if ( counter == 2000 ) {
									LOG.info("===> 2000 user records have been synchronized so far. From now on, only a summary progress log will be written for every 100 users. To continue to see detailed log for every user, please enable Trace level logging. <===");
								}
							} else {
								if (LOG.isTraceEnabled()) {
									LOG.trace("Updating user count: " + counter
											+ ", userName: " + userName + ", groupList: "
											+ groupList);
								} else  {
									if ( counter % 100 == 0) {
										LOG.info("Synced " + counter + " users till now");
									}
								}
							}
						} else {
							// If the user from the search result is present in the group user table,
							// then addorupdate user to ranger admin.
							String userFullName = (userEntry.getNameInNamespace()).toLowerCase();
							LOG.debug("Chekcing if the user " + userFullName + " is part of the retrieved groups");
							if (groupUserTable.containsColumn(userFullName) || groupUserTable.containsColumn(userName)) {
								String transformUserName = userNameTransform(userName);
								try {
									sink.addOrUpdateUser(transformUserName);
								} catch (Throwable t) {
									LOG.error("sink.addOrUpdateUser failed with exception: " + t.getMessage()
									+ ", for user: " + transformUserName);
								}
							}
						}

					}

					// Examine the paged results control response
					Control[] controls = ldapContext.getResponseControls();
					if (controls != null) {
						for (int i = 0; i < controls.length; i++) {
							if (controls[i] instanceof PagedResultsResponseControl) {
								PagedResultsResponseControl prrc =
										(PagedResultsResponseControl)controls[i];
								total = prrc.getResultSize();
								if (total != 0) {
									LOG.debug("END-OF-PAGE total : " + total);
								} else {
									LOG.debug("END-OF-PAGE total : unknown");
								}
								cookie = prrc.getCookie();
							}
						}
					} else {
						LOG.debug("No controls were sent from the server");
					}
					// Re-activate paged results
					if (pagedResultsEnabled)   {
						ldapContext.setRequestControls(new Control[]{
								new PagedResultsControl(PAGE_SIZE, cookie, Control.CRITICAL) });
					}
				} while (cookie != null);
				LOG.info("LdapDeltaUserGroupBuilder.getUsers() completed with user count: "
						+ counter);
				} catch (Throwable t) {
					LOG.error("LdapDeltaUserGroupBuilder.getUsers() failed with exception: " + t);
					LOG.info("LdapDeltaUserGroupBuilder.getUsers() user count: "
							+ counter);
				}
			}
			if (deltaSyncUserTime < highestdeltaSyncUserTime) {
				// Incrementing highestdeltaSyncUserTime (for AD) in order to avoid search record repetition for next sync cycle.
				deltaSyncUserTime = highestdeltaSyncUserTime+1; 
				// Incrementing the highest timestamp value (for Openldap) with 1min in order to avoid search record repetition for next sync cycle.
				deltaSyncUserTimeStamp = dateFormat.format(new Date(highestdeltaSyncUserTime + 60000l));
			}
			
		} finally {
			if (userSearchResultEnum != null) {
				userSearchResultEnum.close();
			}
			if (groupSearchResultEnum != null) {
				groupSearchResultEnum.close();
			}
			closeLdapContext();
		}
	}
	
	private void getGroups(UserGroupSink sink) throws Throwable {
		NamingEnumeration<SearchResult> groupSearchResultEnum = null;
		try {
			createLdapContext();
			int total;
			// Activate paged results
			if (pagedResultsEnabled)   {
				ldapContext.setRequestControls(new Control[]{
						new PagedResultsControl(pagedResultsSize, Control.NONCRITICAL) });
			}
			extendedGroupSearchFilter = "(objectclass=" + groupObjectClass + ")";
			if (groupSearchFilter != null && !groupSearchFilter.trim().isEmpty()) {
				String customFilter = groupSearchFilter.trim();
				if (!customFilter.startsWith("(")) {
					customFilter = "(" + customFilter + ")";
				}
				extendedGroupSearchFilter = extendedGroupSearchFilter + customFilter;
			}
			
			DateFormat dateFormat = new SimpleDateFormat("yyyyMMddhhmmss");
			extendedAllGroupsSearchFilter = "(&"  + extendedGroupSearchFilter + "(|(uSNChanged>=" + deltaSyncGroupTime + ")(modifyTimestamp>=" + deltaSyncGroupTimeStamp + "Z)))";
			
			LOG.info("extendedAllGroupsSearchFilter = " + extendedAllGroupsSearchFilter);
			long highestdeltaSyncGroupTime = deltaSyncGroupTime;
			for (int ou=0; ou<groupSearchBase.length; ou++) {
				byte[] cookie = null;
				int counter = 0;
				try {
					do {
						groupSearchResultEnum = ldapContext
								.search(groupSearchBase[ou], extendedAllGroupsSearchFilter,
										groupSearchControls);
						while (groupSearchResultEnum.hasMore()) {
							final SearchResult groupEntry = groupSearchResultEnum.next();
							if (groupEntry == null) {
								if (LOG.isInfoEnabled())  {
									LOG.info("groupEntry null, skipping sync for the entry");
								}
								continue;
							}
							counter++;
							Attribute groupNameAttr = groupEntry.getAttributes().get(groupNameAttribute);
							if (groupNameAttr == null) {
								if (LOG.isInfoEnabled())  {
									LOG.info(groupNameAttribute + " empty for entry " + groupEntry.getNameInNamespace() +
											", skipping sync");
								}
								continue;
							}
							String gName = (String) groupNameAttr.get();
							String transformGroupName = groupNameTransform(gName);
							// If group based search is enabled, then
							// update the group name to ranger admin
							// check for group members and populate userInfo object with user's full name and group mapping
							if (groupSearchFirstEnabled) {
								LOG.debug("Update Ranger admin with " + transformGroupName);
								sink.addOrUpdateGroup(transformGroupName);
							}
							Attribute timeStampAttr  = groupEntry.getAttributes().get("uSNChanged");
							if (timeStampAttr != null) {
								String uSNChangedVal = (String) timeStampAttr.get();
								long currentDeltaSyncTime = Long.parseLong(uSNChangedVal);
								if (currentDeltaSyncTime > highestdeltaSyncGroupTime) {
									highestdeltaSyncGroupTime = currentDeltaSyncTime;
								}
							} else {
								timeStampAttr = groupEntry.getAttributes().get("modifytimestamp");
								if (timeStampAttr != null) {
									String timeStampVal = (String) timeStampAttr.get();
									Date parseDate = dateFormat.parse(timeStampVal);						
									long currentDeltaSyncTime = parseDate.getTime();
									LOG.info("timeStampVal = " + timeStampVal + "and currentDeltaSyncTime = " + currentDeltaSyncTime);
									if (currentDeltaSyncTime > highestdeltaSyncGroupTime) {
										highestdeltaSyncGroupTime = currentDeltaSyncTime;
										deltaSyncGroupTimeStamp = timeStampVal;
									}
								}
							}
							Attribute groupMemberAttr = groupEntry.getAttributes().get(groupMemberAttributeName);
							int userCount = 0;
							if (groupMemberAttr == null || groupMemberAttr.size() <= 0) {
								LOG.info("No members available for " + gName);
								continue;
							}
							
							NamingEnumeration<?> userEnum = groupMemberAttr.getAll();
							while (userEnum.hasMore()) {
								String originalUserFullName = (String) userEnum.next();
								if (originalUserFullName == null || originalUserFullName.trim().isEmpty()) {
									continue;
								}
								userCount++;
								String userName = getShortUserName(originalUserFullName);
								if (groupSearchFirstEnabled && !userSearchEnabled) {
									String transformUserName = userNameTransform(userName);
									try {
										sink.addOrUpdateUser(transformUserName);
									} catch (Throwable t) {
										LOG.error("sink.addOrUpdateUser failed with exception: " + t.getMessage()
										+ ", for user: " + transformUserName);
									}
								}
								groupUserTable.put(gName, userName, userName);
							}
							LOG.info("No. of members in the group " + gName + " = " + userCount);
						}
						// Examine the paged results control response
						Control[] controls = ldapContext.getResponseControls();
						if (controls != null) {
							for (int i = 0; i < controls.length; i++) {
								if (controls[i] instanceof PagedResultsResponseControl) {
									PagedResultsResponseControl prrc =
											(PagedResultsResponseControl)controls[i];
									total = prrc.getResultSize();
									if (total != 0) {
										LOG.debug("END-OF-PAGE total : " + total);
									} else {
										LOG.debug("END-OF-PAGE total : unknown");
									}
									cookie = prrc.getCookie();
								}
							}
						} else {
							LOG.debug("No controls were sent from the server");
						}
						// Re-activate paged results
						if (pagedResultsEnabled)   {
							ldapContext.setRequestControls(new Control[]{
									new PagedResultsControl(PAGE_SIZE, cookie, Control.CRITICAL) });
						}
					} while (cookie != null);
					LOG.info("LdapDeltaUserGroupBuilder.getGroups() completed with group count: "
							+ counter);
				} catch (Throwable t) {
					LOG.error("LdapDeltaUserGroupBuilder.getGroups() failed with exception: " + t); 
					LOG.info("LdapDeltaUserGroupBuilder.getGroups() group count: "
							+ counter);
				}
			}
			if (deltaSyncGroupTime < highestdeltaSyncGroupTime) {
				// Incrementing highestdeltaSyncGroupTime (for AD) in order to avoid search record repetition for next sync cycle.
				deltaSyncGroupTime = highestdeltaSyncGroupTime+1;
				// Incrementing the highest timestamp value (for OpenLdap) with 1min in order to avoid search record repetition for next sync cycle.
				deltaSyncGroupTimeStamp = dateFormat.format(new Date(highestdeltaSyncGroupTime + 60000l)); 
			}

		} finally {
			if (groupSearchResultEnum != null) {
				groupSearchResultEnum.close();
			}
			closeLdapContext();
		}
	}

	
	private static String getShortGroupName(String longGroupName) throws InvalidNameException {
		if (longGroupName == null) {
			return null;
		}
		StringTokenizer stc = new StringTokenizer(longGroupName, ",");
		String firstToken = stc.nextToken();
		StringTokenizer ste = new StringTokenizer(firstToken, "=");
		String groupName =  ste.nextToken();
		if (ste.hasMoreTokens()) {
			groupName = ste.nextToken();
		}
		groupName = groupName.trim();
		LOG.info("longGroupName: " + longGroupName + ", groupName: " + groupName);
		return groupName;
	}
	
	private static String getShortUserName(String longUserName) throws InvalidNameException {
		if (longUserName == null) {
			return null;
		}
		StringTokenizer stc = new StringTokenizer(longUserName, ",");
		String firstToken = stc.nextToken();
		StringTokenizer ste = new StringTokenizer(firstToken, "=");
		String userName =  ste.nextToken();
		if (ste.hasMoreTokens()) {
			userName = ste.nextToken();
		}
		userName = userName.trim();
		LOG.info("longUserName: " + longUserName + ", userName: " + userName);
		return userName;
	}
	
	private String userNameTransform(String userName) {
		//String userNameTransform = userName;
		if (userNameCaseConversionFlag) {
			if (userNameLowerCaseFlag) {
				userName = userName.toLowerCase();
			}
			else {
				userName = userName.toUpperCase();
			}
		}

		if (userNameRegExInst != null) {
			userName = userNameRegExInst.transform(userName);
		}

		return userName;
	}
	
	private String groupNameTransform(String groupName) {
		//String userNameTransform = userName;
		if (groupNameCaseConversionFlag) {
			if (groupNameLowerCaseFlag) {
				groupName = groupName.toLowerCase();
			}
			else {
				groupName = groupName.toUpperCase();
			}
		}

		if (groupNameRegExInst != null) {
			groupName = groupNameRegExInst.transform(groupName);
		}

		return groupName;
	}
	
}

/*class UserInfo {
	private String userName;
	private String userFullName;
	private Set<String> groupList;
	
	public UserInfo(String userName, String userFullName) {
		this.userName = userName;
		this.userFullName = userFullName;
		this.groupList = new HashSet<String>();
	}
	
	public void updateUserName(String userName) {
		this.userName = userName;
	}
	
	public String getUserName() {
		return userName;
	}
	public String getUserFullName() {
		return userFullName;
	}
	public void addGroups(Set<String> groups) {
		groupList.addAll(groups);
	}
	public void addGroup(String group) {
		groupList.add(group);
	}
	public List<String> getGroups() {
		return (new ArrayList<String>(groupList));
	}
}*/
