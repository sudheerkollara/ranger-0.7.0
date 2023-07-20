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

package org.apache.ranger.authorization.hbase;


import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Type;
import java.util.List;

import org.apache.ranger.authorization.hbase.TestPolicyEngine.PolicyEngineTestCase.TestData;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.policyengine.RangerAccessResource;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;


public class TestPolicyEngine {
	static RangerBasePlugin plugin = null;
	static Gson             gsonBuilder  = null;


	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		plugin = new RangerBasePlugin("hbase", "hbase");
		gsonBuilder = new GsonBuilder().setDateFormat("yyyyMMdd-HH:mm:ss.SSS-Z")
									   .setPrettyPrinting()
									   .registerTypeAdapter(RangerAccessRequest.class, new RangerAccessRequestDeserializer())
									   .registerTypeAdapter(RangerAccessResource.class,  new RangerResourceDeserializer())
									   .create();
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/*
	@Test
	public void testPolicyEngine_hbase() {
		String[] hbaseTestResourceFiles = { "/policyengine/test_policyengine_hbase.json" };

		runTestsFromResourceFiles(hbaseTestResourceFiles);
		
		// lets use that policy engine now
		AuthorizationSession session = new AuthorizationSession(plugin);
		User user = mock(User.class);
		when(user.getShortName()).thenReturn("user1");
		when(user.getGroupNames()).thenReturn(new String[] { "users" });
		session.access("read")
			.user(user)
			.table("finance")
			.buildRequest()
			.authorize();
		assertTrue(session.isAuthorized());
		try {
			session.publishResults();
		} catch (AccessDeniedException e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		
		when(user.getShortName()).thenReturn("user1");
		when(user.getGroupNames()).thenReturn(new String[] { "users" });
		session.access("write")
			.buildRequest()
			.authorize();
		assertFalse(session.isAuthorized());
		try {
			session.publishResults();
			fail("Should have throw exception on denied request!");
		} catch (AccessDeniedException e) {
		}
		
	}

	private void runTestsFromResourceFiles(String[] resourceNames) {
		for(String resourceName : resourceNames) {
			InputStream       inStream = this.getClass().getResourceAsStream(resourceName);
			InputStreamReader reader   = new InputStreamReader(inStream);

			runTests(reader, resourceName);
		}
	}

	private void runTests(InputStreamReader reader, String testName) {
		try {
			PolicyEngineTestCase testCase = gsonBuilder.fromJson(reader, PolicyEngineTestCase.class);

			assertTrue("invalid input: " + testName, testCase != null && testCase.serviceDef != null && testCase.policies != null && testCase.tests != null);

			plugin.getPolicyRefresher().getPolicyEngine().setPolicies(testCase.serviceName, testCase.serviceDef, testCase.policies);
			boolean justBuildingPolicyEngine = true;
			if (justBuildingPolicyEngine) {
				return;
			} else {
				for(TestData test : testCase.tests) {
					RangerAccessResult expected = test.result;
					RangerAccessResult result   = plugin.isAccessAllowed(test.request, null);
	
					assertNotNull(test.name, result);
					assertEquals(test.name, expected.getIsAllowed(), result.getIsAllowed());
				}
			}
		} catch(Throwable excp) {
			excp.printStackTrace();
		}
		
	}
	*/

	static class PolicyEngineTestCase {
		public String             serviceName;
		public RangerServiceDef   serviceDef;
		public List<RangerPolicy> policies;
		public List<TestData>     tests;
		
		class TestData {
			public String              name;
			public RangerAccessRequest request;
			public RangerAccessResult  result;
		}
	}
	
	static class RangerAccessRequestDeserializer implements JsonDeserializer<RangerAccessRequest> {
		@Override
		public RangerAccessRequest deserialize(JsonElement jsonObj, Type type,
				JsonDeserializationContext context) throws JsonParseException {
			RangerAccessRequestImpl ret = gsonBuilder.fromJson(jsonObj, RangerAccessRequestImpl.class);

			ret.setAccessType(ret.getAccessType()); // to force computation of isAccessTypeAny and isAccessTypeDelegatedAdmin

			return ret;
		}
	}
	
	static class RangerResourceDeserializer implements JsonDeserializer<RangerAccessResource> {
		@Override
		public RangerAccessResource deserialize(JsonElement jsonObj, Type type,
				JsonDeserializationContext context) throws JsonParseException {
			return gsonBuilder.fromJson(jsonObj, RangerAccessResourceImpl.class);
		}
	}
}
