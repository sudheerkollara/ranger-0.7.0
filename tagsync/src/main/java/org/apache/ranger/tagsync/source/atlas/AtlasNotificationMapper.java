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

package org.apache.ranger.tagsync.source.atlas;

import org.apache.atlas.AtlasException;
import org.apache.atlas.notification.entity.EntityNotification;
import org.apache.atlas.typesystem.IReferenceableInstance;
import org.apache.atlas.typesystem.IStruct;
import org.apache.atlas.typesystem.persistence.Id;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ranger.plugin.model.RangerServiceResource;
import org.apache.ranger.plugin.model.RangerTag;
import org.apache.ranger.plugin.model.RangerTagDef;
import org.apache.ranger.plugin.model.RangerTagDef.RangerTagAttributeDef;
import org.apache.ranger.plugin.util.ServiceTags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AtlasNotificationMapper {
	private static final Log LOG = LogFactory.getLog(AtlasNotificationMapper.class);


	private static Map<String, Long> unhandledEventTypes = new HashMap<String, Long>();

	private static void logUnhandledEntityNotification(EntityNotification entityNotification) {

		final int REPORTING_INTERVAL_FOR_UNHANDLED_ENTITYTYPE_IN_MILLIS = 5 * 60 * 1000; // 5 minutes

		boolean loggingNeeded = false;
		String entityTypeName = entityNotification != null && entityNotification.getEntity() != null ?
				entityNotification.getEntity().getTypeName() : null;

		if (entityTypeName != null) {
			Long timeInMillis = unhandledEventTypes.get(entityTypeName);
			long currentTimeInMillis = System.currentTimeMillis();
			if (timeInMillis == null ||
					(currentTimeInMillis - timeInMillis) >= REPORTING_INTERVAL_FOR_UNHANDLED_ENTITYTYPE_IN_MILLIS) {
				unhandledEventTypes.put(entityTypeName, currentTimeInMillis);
				loggingNeeded = true;
			}
		} else {
			LOG.error("EntityNotification contains NULL entity or NULL entity-type");
		}

		if (loggingNeeded) {
			LOG.warn("Ignoring entity notification of type " + entityTypeName);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("Ignoring entity notification of type " + entityTypeName);
		}
	}

	@SuppressWarnings("unchecked")
	public static ServiceTags processEntityNotification(EntityNotification entityNotification) {

		ServiceTags ret = null;

		if (isNotificationHandled(entityNotification)) {
			try {
				IReferenceableInstance entity = entityNotification.getEntity();

				if (entity != null && AtlasResourceMapperUtil.isEntityTypeHandled(entity.getTypeName())) {
					AtlasEntityWithTraits entityWithTraits = new AtlasEntityWithTraits(entity, entityNotification.getAllTraits());
					if (entityNotification.getOperationType() == EntityNotification.OperationType.ENTITY_DELETE) {
						ret = buildServiceTagsForEntityDeleteNotification(entityWithTraits);
					} else {
						if (entity.getId().getState() == Id.EntityState.ACTIVE) {
							ret = buildServiceTags(entityWithTraits, null);
						} else {
							if (LOG.isDebugEnabled()) {
								LOG.debug("Ignoring entityNotification for entity that is not ACTIVE: " + entityWithTraits);
							}
						}
					}
				} else {
					logUnhandledEntityNotification(entityNotification);
				}

			} catch (Exception exception) {
				LOG.error("createServiceTags() failed!! ", exception);
			}
		}
		return ret;
	}

	public static Map<String, ServiceTags> processAtlasEntities(List<AtlasEntityWithTraits> atlasEntities) {
		Map<String, ServiceTags> ret = null;

		try {
			ret = buildServiceTags(atlasEntities);
		} catch (Exception exception) {
			LOG.error("Failed to build serviceTags", exception);
		}

		return ret;
	}

	static private boolean isNotificationHandled(EntityNotification entityNotification) {
		boolean ret = false;

		EntityNotification.OperationType opType = entityNotification.getOperationType();

		if(opType != null) {
			switch (opType) {
				case ENTITY_CREATE: {
					LOG.debug("ENTITY_CREATE notification is not handled, as Ranger will get necessary information from any subsequent TRAIT_ADDED notification");
					break;
				}
				case ENTITY_UPDATE:
				case ENTITY_DELETE:
				case TRAIT_ADD:
				case TRAIT_DELETE: {
					ret = true;
					break;
				}
				default:
					LOG.error(opType + ": unknown notification received - not handled");
			}
		}

		return ret;
	}

	static private ServiceTags buildServiceTagsForEntityDeleteNotification(AtlasEntityWithTraits entityWithTraits) throws Exception {
		final ServiceTags ret;

		IReferenceableInstance entity = entityWithTraits.getEntity();

		String guid = entity.getId()._getId();
		if (StringUtils.isNotBlank(guid)) {
			ret = new ServiceTags();
			RangerServiceResource serviceResource = new RangerServiceResource();
			serviceResource.setGuid(guid);
			ret.getServiceResources().add(serviceResource);
		} else {
			ret = buildServiceTags(entityWithTraits, null);
			if (ret != null) {
				// tag-definitions should NOT be deleted as part of service-resource delete
				ret.setTagDefinitions(MapUtils.EMPTY_MAP);
				// Ranger deletes tags associated with deleted service-resource
				ret.setTags(MapUtils.EMPTY_MAP);
			}
		}

		if (ret != null) {
			ret.setOp(ServiceTags.OP_DELETE);
		}

		return ret;
	}

	static private Map<String, ServiceTags> buildServiceTags(List<AtlasEntityWithTraits> entitiesWithTraits) throws Exception {

		Map<String, ServiceTags> ret = new HashMap<String, ServiceTags>();

		for (AtlasEntityWithTraits element : entitiesWithTraits) {
			IReferenceableInstance entity = element.getEntity();
			if (entity != null && entity.getId().getState() == Id.EntityState.ACTIVE) {
				buildServiceTags(element, ret);
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Ignoring entity because its State is not ACTIVE: " + element);
				}
			}
		}

		// Remove duplicate tag definitions
		if(CollectionUtils.isNotEmpty(ret.values())) {
			for (ServiceTags serviceTag : ret.values()) {
				if(MapUtils.isNotEmpty(serviceTag.getTagDefinitions())) {
					Map<String, RangerTagDef> uniqueTagDefs = new HashMap<String, RangerTagDef>();

					for (RangerTagDef tagDef : serviceTag.getTagDefinitions().values()) {
						RangerTagDef existingTagDef = uniqueTagDefs.get(tagDef.getName());

						if (existingTagDef == null) {
							uniqueTagDefs.put(tagDef.getName(), tagDef);
						} else {
							if(CollectionUtils.isNotEmpty(tagDef.getAttributeDefs())) {
								for(RangerTagAttributeDef tagAttrDef : tagDef.getAttributeDefs()) {
									boolean attrDefExists = false;

									if(CollectionUtils.isNotEmpty(existingTagDef.getAttributeDefs())) {
										for(RangerTagAttributeDef existingTagAttrDef : existingTagDef.getAttributeDefs()) {
											if(StringUtils.equalsIgnoreCase(existingTagAttrDef.getName(), tagAttrDef.getName())) {
												attrDefExists = true;
												break;
											}
										}
									}

									if(! attrDefExists) {
										existingTagDef.getAttributeDefs().add(tagAttrDef);
									}
								}
							}
						}
					}

					serviceTag.getTagDefinitions().clear();
					for(RangerTagDef tagDef : uniqueTagDefs.values()) {
						serviceTag.getTagDefinitions().put(tagDef.getId(), tagDef);
					}
				}
			}
		}

		if (MapUtils.isNotEmpty(ret)) {
			for (Map.Entry<String, ServiceTags> entry : ret.entrySet()) {
				ServiceTags serviceTags = entry.getValue();
				serviceTags.setOp(ServiceTags.OP_REPLACE);
			}
		}
		return ret;
	}

	static private ServiceTags buildServiceTags(AtlasEntityWithTraits entityWithTraits, Map<String, ServiceTags> serviceTagsMap) throws Exception {
		ServiceTags            ret             = null;
		IReferenceableInstance entity          = entityWithTraits.getEntity();
		RangerServiceResource  serviceResource = AtlasResourceMapperUtil.getRangerServiceResource(entity);

		if (serviceResource != null) {

			List<RangerTag>     tags        = getTags(entityWithTraits);
			List<RangerTagDef>  tagDefs     = getTagDefs(entityWithTraits);
			String              serviceName = serviceResource.getServiceName();

			ret = createOrGetServiceTags(serviceTagsMap, serviceName);

			if (serviceTagsMap == null || CollectionUtils.isNotEmpty(tags)) {

				serviceResource.setId((long) ret.getServiceResources().size());
				ret.getServiceResources().add(serviceResource);

				List<Long> tagIds = new ArrayList<Long>();

				if (CollectionUtils.isNotEmpty(tags)) {
					for (RangerTag tag : tags) {
						tag.setId((long) ret.getTags().size());
						ret.getTags().put(tag.getId(), tag);

						tagIds.add(tag.getId());
					}
				}
				ret.getResourceToTagIds().put(serviceResource.getId(), tagIds);

				if (CollectionUtils.isNotEmpty(tagDefs)) {
					for (RangerTagDef tagDef : tagDefs) {
						tagDef.setId((long) ret.getTagDefinitions().size());
						ret.getTagDefinitions().put(tagDef.getId(), tagDef);
					}
				}
			} else {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Entity " + entityWithTraits + " does not have any tags associated with it when full-sync is being done.");
					LOG.debug("Will not add this entity to serviceTags, so that this entity, if exists,  will be removed from ranger");
				}
			}
		} else {
			LOG.error("Failed to build serviceResource for entity:" + entity.getId()._getId());
		}

		return ret;
	}

	static private ServiceTags createOrGetServiceTags(Map<String, ServiceTags> serviceTagsMap, String serviceName) {
		ServiceTags ret = serviceTagsMap == null ? null : serviceTagsMap.get(serviceName);

		if (ret == null) {
			ret = new ServiceTags();

			if (serviceTagsMap != null) {
				serviceTagsMap.put(serviceName, ret);
			}

			ret.setOp(ServiceTags.OP_ADD_OR_UPDATE);
			ret.setServiceName(serviceName);
		}

		return ret;
	}

	static private List<RangerTag> getTags(AtlasEntityWithTraits entityWithTraits) {
		List<RangerTag> ret = new ArrayList<RangerTag>();

		if(entityWithTraits != null && CollectionUtils.isNotEmpty(entityWithTraits.getAllTraits())) {
			List<IStruct> traits = entityWithTraits.getAllTraits();

			for (IStruct trait : traits) {
				Map<String, String> tagAttrs = new HashMap<String, String>();

				try {
					Map<String, Object> attrs = trait.getValuesMap();

					if(MapUtils.isNotEmpty(attrs)) {
						for (Map.Entry<String, Object> attrEntry : attrs.entrySet()) {
							String attrName  = attrEntry.getKey();
							Object attrValue = attrEntry.getValue();

							tagAttrs.put(attrName, attrValue != null ? attrValue.toString() : null);
						}
					}
				} catch (AtlasException exception) {
					LOG.error("Could not get values for trait:" + trait.getTypeName(), exception);
				}

				ret.add(new RangerTag(null, trait.getTypeName(), tagAttrs, RangerTag.OWNER_SERVICERESOURCE));
			}
		}

		return ret;
	}

	static private List<RangerTagDef> getTagDefs(AtlasEntityWithTraits entityWithTraits) {
		List<RangerTagDef> ret = new ArrayList<RangerTagDef>();

		if(entityWithTraits != null && CollectionUtils.isNotEmpty(entityWithTraits.getAllTraits())) {
			List<IStruct> traits = entityWithTraits.getAllTraits();

			for (IStruct trait : traits) {
				RangerTagDef tagDef = new RangerTagDef(trait.getTypeName(), "Atlas");

				try {
					Map<String, Object> attrs = trait.getValuesMap();

					if(MapUtils.isNotEmpty(attrs)) {
						for (String attrName : attrs.keySet()) {
							tagDef.getAttributeDefs().add(new RangerTagAttributeDef(attrName, "string"));
						}
					}
				} catch (AtlasException exception) {
					LOG.error("Could not get values for trait:" + trait.getTypeName(), exception);
				}

				ret.add(tagDef);
			}
		}

		return ret;
	}
}
