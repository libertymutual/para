/*
 * Copyright 2013-2014 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.rest;

import com.erudika.para.Para;
import com.erudika.para.core.App;
import com.erudika.para.core.ParaObject;
import com.erudika.para.persistence.DAO;
import com.erudika.para.rest.RestUtils.GenericExceptionMapper;
import com.erudika.para.search.Search;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.HumanTime;
import com.erudika.para.utils.Pager;
import com.erudika.para.utils.Utils;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.glassfish.jersey.process.Inflector;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the main REST API configuration class which defines all endpoints for all resources
 * and the way API request will be handled. This is API version 1.0.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public final class Api1 extends ResourceConfig {

	public static final String PATH = "/v1/";

	private static final Logger logger = LoggerFactory.getLogger(Api1.class);

	private static final String JSON = MediaType.APPLICATION_JSON; // + "; charset=utf-8"?
	private static final String GET = HttpMethod.GET;
	private static final String PUT = HttpMethod.PUT;
	private static final String POST = HttpMethod.POST;
	private static final String DELETE = HttpMethod.DELETE;

	private final DAO dao;
	private final Search search;

	/**
	 * Initializes all of the API resources.
	 */
	public Api1() {
		dao = Para.getDAO()	;
		search = Para.getSearch();

		if (!Config.REST_ENABLED) {
			return;
		}

		setApplicationName(Config.APP_NAME_NS);

		register(new JacksonJsonProvider(Utils.getJsonMapper()));
		register(GenericExceptionMapper.class);

		// core objects CRUD API
		registerCrudApi("{type}", typeCrudHandler(), linksHandler());

		// search API
		Resource.Builder searchRes = Resource.builder("search/{querytype}");
		searchRes.addMethod(GET).produces(JSON).handledBy(searchHandler(null));
		registerResources(searchRes.build());

		// first time setup
		Resource.Builder setupRes = Resource.builder("setup");
		setupRes.addMethod(GET).produces(JSON).handledBy(setupHandler());
		registerResources(setupRes.build());

		// reset API keys
		Resource.Builder keysRes = Resource.builder("newkeys");
		keysRes.addMethod(POST).produces(JSON).handledBy(keysHandler());
		registerResources(keysRes.build());

		// user-defined types
		Resource.Builder typesRes = Resource.builder("types");
		typesRes.addMethod(GET).produces(JSON).handledBy(listTypesHandler());
		registerResources(typesRes.build());

		// util functions API
		Resource.Builder utilsRes = Resource.builder("utils/{method}");
		utilsRes.addMethod(GET).produces(JSON).handledBy(utilsHandler());
		registerResources(utilsRes.build());
	}

	private void registerCrudApi(String path, Inflector<ContainerRequestContext, Response> handler,
			Inflector<ContainerRequestContext, Response> linksHandler) {
		Resource.Builder core = Resource.builder(path);
		// list endpoints (both do the same thing)
		core.addMethod(GET).produces(JSON).handledBy(handler);
		core.addChildResource("search/{querytype}").addMethod(GET).produces(JSON).handledBy(handler);
		// CRUD endpoints (non-batch)
		core.addMethod(POST).produces(JSON).consumes(JSON).handledBy(handler);
		core.addChildResource("{id}").addMethod(GET).produces(JSON).handledBy(handler);
		core.addChildResource("{id}").addMethod(PUT).produces(JSON).consumes(JSON).handledBy(handler);
		core.addChildResource("{id}").addMethod(DELETE).produces(JSON).handledBy(handler);
		// links CRUD endpoints
		core.addChildResource("{id}/links/{type2}/{id2}").addMethod(GET).produces(JSON).handledBy(linksHandler);
		core.addChildResource("{id}/links/{id2}").addMethod(POST).produces(JSON).handledBy(linksHandler);
		core.addChildResource("{id}/links/{type2}/{id2}").addMethod(DELETE).produces(JSON).handledBy(linksHandler);
		// CRUD endpoints (batch)
		Resource.Builder batch = Resource.builder("_batch");
		batch.addMethod(POST).produces(JSON).consumes(JSON).handledBy(batchCreateHandler());
		batch.addMethod(GET).produces(JSON).handledBy(batchReadHandler());
		batch.addMethod(PUT).produces(JSON).consumes(JSON).handledBy(batchUpdateHandler());
		batch.addMethod(DELETE).produces(JSON).consumes(JSON).handledBy(batchDeleteHandler());

		registerResources(core.build());
		registerResources(batch.build());
	}

	private Inflector<ContainerRequestContext, Response> utilsHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
				String method = ctx.getUriInfo().getPathParameters().getFirst("method");
				method = StringUtils.isBlank(method) ? params.getFirst("method") : method;
				if ("newid".equals(method)) {
					return Response.ok(Utils.getNewId()).build();
				} else if ("timestamp".equals(method)) {
					return Response.ok(Utils.timestamp()).build();
				} else if ("formatdate".equals(method)) {
					String format = params.getFirst("format");
					String locale = params.getFirst("locale");
					Locale loc = LocaleUtils.toLocale(locale);
					return Response.ok(Utils.formatDate(format, loc)).build();
				} else if ("formatmessage".equals(method)) {
					String msg = params.getFirst("message");
					Object[] paramz = params.get("fields").toArray();
					return Response.ok(Utils.formatMessage(msg, paramz)).build();
				} else if ("nospaces".equals(method)) {
					String str = params.getFirst("string");
					String repl = params.getFirst("replacement");
					return Response.ok(Utils.noSpaces(str, repl)).build();
				} else if ("nosymbols".equals(method)) {
					String str = params.getFirst("string");
					return Response.ok(Utils.stripAndTrim(str)).build();
				} else if ("md2html".equals(method)) {
					String md = params.getFirst("md");
					return Response.ok(Utils.markdownToHtml(md)).build();
				} else if ("timeago".equals(method)) {
					String d = params.getFirst("delta");
					long delta = NumberUtils.toLong(d, 1);
					return Response.ok(HumanTime.approximately(delta)).build();
				}
				return RestUtils.getStatusResponse(Response.Status.BAD_REQUEST, "Method not specified.");
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> typeCrudHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String typePlural = ctx.getUriInfo().getPathParameters().getFirst(Config._TYPE);
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				if (app != null && !StringUtils.isBlank(typePlural)) {
					String type = RestUtils.getCoreTypes().get(typePlural);
					if (type == null) {
						type = app.getDatatypes().get(typePlural);
					}
					if (type == null) {
						type = typePlural;
					}
					return crudHandler(app, type).apply(ctx);
				}
				return RestUtils.getStatusResponse(Response.Status.NOT_FOUND, "App not found: " + appid);
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> crudHandler(final App app, final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String id = ctx.getUriInfo().getPathParameters().getFirst(Config._ID);
				if (StringUtils.isBlank(id)) {
					if (GET.equals(ctx.getMethod())) {
						return searchHandler(type).apply(ctx);
					} else if (POST.equals(ctx.getMethod())) {
						return createHandler(app, type).apply(ctx);
					} else if (ctx.getUriInfo().getPath().contains("search")) {
						return searchHandler(type).apply(ctx);
					}
				} else {
					if (GET.equals(ctx.getMethod())) {
						return readHandler(app, type).apply(ctx);
					} else if (PUT.equals(ctx.getMethod())) {
						return updateHandler(app, type).apply(ctx);
					} else if (DELETE.equals(ctx.getMethod())) {
						return deleteHandler(app, type).apply(ctx);
					}
				}
				return RestUtils.getStatusResponse(Response.Status.NOT_FOUND, "Type '" + type + "' not found.");
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> linksHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
				MultivaluedMap<String, String> pathp = ctx.getUriInfo().getPathParameters();
				String id = pathp.getFirst(Config._ID);
				String type = pathp.getFirst(Config._TYPE);
				String id2 = pathp.getFirst("id2");
				String type2 = pathp.getFirst("type2");
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());

				id2 = StringUtils.isBlank(id2) ? params.getFirst(Config._ID) : id2;
				type2 = StringUtils.isBlank(type2) ? params.getFirst(Config._TYPE) : type2;

				ParaObject pobj = Utils.toObject(type);
				pobj.setId(id);
				pobj = dao.read(appid, pobj.getId());

				Pager pager = new Pager();
				pager.setPage(NumberUtils.toLong(params.getFirst("page"), 0));
				pager.setSortby(params.getFirst("sort"));
				pager.setDesc(Boolean.parseBoolean(params.containsKey("desc") ? params.getFirst("desc") : "true"));
				pager.setLimit(NumberUtils.toInt(params.getFirst("limit"), pager.getLimit()));

				String childrenOnly = params.getFirst("childrenonly");

				if (pobj != null) {
					if (POST.equals(ctx.getMethod())) {
						if (id2 != null) {
							String linkid = pobj.link(id2);
							if (linkid == null) {
								return RestUtils.getStatusResponse(Response.Status.BAD_REQUEST,
										"Failed to create link.");
							} else {
								return Response.ok(linkid).build();
							}
						} else {
							return RestUtils.getStatusResponse(Response.Status.BAD_REQUEST,
									"Parameters 'type' and 'id' are missing.");
						}
					} else if (GET.equals(ctx.getMethod())) {
						Map<String, Object> result = new HashMap<String, Object>();
						if (type2 != null) {
							if (id2 != null) {
								return Response.ok(pobj.isLinked(type2, id2)).build();
							} else {
								List<ParaObject> items = new ArrayList<ParaObject>();
								if (childrenOnly == null) {
									if (params.containsKey("count")) {
										pager.setCount(pobj.countLinks(type2));
									} else {
										items = pobj.getLinkedObjects(type2, pager);
									}
								} else {
									if (params.containsKey("count")) {
										pager.setCount(pobj.countChildren(type2));
									} else {
										if (params.containsKey("field") && params.containsKey("term")) {
											items = pobj.getChildren(type2, params.getFirst("field"),
													params.getFirst("term"), pager);
										} else {
											items = pobj.getChildren(type2, pager);
										}
									}
								}
								result.put("items", items);
								result.put("totalHits", pager.getCount());
								return Response.ok(result).build();
							}
						} else {
							return RestUtils.getStatusResponse(Response.Status.BAD_REQUEST,
									"Parameter 'type' is missing.");
						}
					} else if (DELETE.equals(ctx.getMethod())) {
						if (type2 == null && id2 == null) {
							pobj.unlinkAll();
						} else if (type2 != null) {
							if (id2 != null) {
								pobj.unlink(type2, id2);
							} else if (childrenOnly != null) {
								pobj.deleteChildren(type2);
							}
						}
						return Response.ok().build();
					}
				}
				return RestUtils.getStatusResponse(Response.Status.NOT_FOUND, "Object not found: " + id);
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> listTypesHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				if (app != null) {
					Map<String, String> allTypes = new HashMap<String, String>(app.getDatatypes());
					allTypes.putAll(RestUtils.getCoreTypes());
					return Response.ok(allTypes).build();
				}
				return RestUtils.getStatusResponse(Response.Status.NOT_FOUND, "App not found: " + appid);
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> keysHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				if (app != null) {
					app.resetSecret();
					app.update();
					Map<String, String> creds = app.getCredentials();
					creds.put("info", "Save the secret key! It is showed only once!");
					return Response.ok(creds).build();
				}
				return RestUtils.getStatusResponse(Response.Status.NOT_FOUND, "App not found: " + appid);
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> setupHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				App app = new App(Config.APP_NAME_NS); // the root app name
				if (app.exists()) {
					return RestUtils.getStatusResponse(Response.Status.OK, "All set!");
				} else {
					app.setName(Config.APP_NAME);
					app.create();
					Map<String, String> creds = app.getCredentials();
					creds.put("info", "Save the secret key! It is showed only once!");
					return Response.ok(creds).build();
				}
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> createHandler(final App app, final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				return RestUtils.getCreateResponse(app, type, ctx.getEntityStream());
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> readHandler(final App app, final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				ParaObject obj = Utils.toObject(type);
				obj.setId(ctx.getUriInfo().getPathParameters().getFirst(Config._ID));
				return RestUtils.getReadResponse(dao.read(app.getAppIdentifier(), obj.getId()));
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> updateHandler(final App app, final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				ParaObject obj = Utils.toObject(type);
				obj.setType(type);
				obj.setId(ctx.getUriInfo().getPathParameters().getFirst(Config._ID));
				return RestUtils.getUpdateResponse(app, dao.read(app.getAppIdentifier(), obj.getId()),
						ctx.getEntityStream());
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> deleteHandler(final App app, final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				ParaObject obj = Utils.toObject(type);
				obj.setType(type);
				obj.setId(ctx.getUriInfo().getPathParameters().getFirst(Config._ID));
				return RestUtils.getDeleteResponse(app, obj);
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> batchCreateHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				return RestUtils.getBatchCreateResponse(app, ctx.getEntityStream());
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> batchReadHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				return RestUtils.getBatchReadResponse(app, ctx.getUriInfo().getQueryParameters().get("ids"));
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> batchUpdateHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				return RestUtils.getBatchUpdateResponse(app, ctx.getEntityStream());
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> batchDeleteHandler() {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				return RestUtils.getBatchDeleteResponse(app, ctx.getUriInfo().getQueryParameters().get("ids"));
			}
		};
	}

	private Inflector<ContainerRequestContext, Response> searchHandler(final String type) {
		return new Inflector<ContainerRequestContext, Response>() {
			public Response apply(ContainerRequestContext ctx) {
				String appid = RestUtils.getPrincipalAppid(ctx.getSecurityContext().getUserPrincipal());
				App app = RestUtils.getApp(appid);
				MultivaluedMap<String, String> params = ctx.getUriInfo().getQueryParameters();
				String queryType = ctx.getUriInfo().getPathParameters().getFirst("querytype");
				return Response.ok(buildQueryAndSearch(app, queryType, params, type)).build();
			}
		};
	}

	private <P extends ParaObject> Map<String, Object> buildQueryAndSearch(App app, String queryType,
			MultivaluedMap<String, String> params, String typeOverride) {
		String query = params.containsKey("q") ? params.getFirst("q") : "*";
		String type = (typeOverride != null) ? typeOverride : params.getFirst(Config._TYPE);
		String appid = app.isShared() ? ("_" + Config.SEPARATOR + app.getAppIdentifier()) : app.getAppIdentifier();

		Pager pager = new Pager();
		pager.setPage(NumberUtils.toLong(params.getFirst("page"), 0));
		pager.setSortby(params.getFirst("sort"));
		pager.setDesc(Boolean.parseBoolean(params.containsKey("desc") ? params.getFirst("desc") : "true"));
		pager.setLimit(NumberUtils.toInt(params.getFirst("limit"), pager.getLimit()));

		queryType = StringUtils.isBlank(queryType) ? params.getFirst("querytype") : queryType;
		Map<String, Object> result = new HashMap<String, Object>();
		List<P> items = new ArrayList<P>();

		if ("id".equals(queryType)) {
			String id = params.containsKey(Config._ID) ? params.getFirst(Config._ID) : null;
			P obj = search.findById(appid, id);
			if (obj != null) {
				items = Collections.singletonList(obj);
				pager.setCount(1);
			}
		} else if ("ids".equals(queryType)) {
			List<String> ids = params.get("ids");
			items = search.findByIds(appid, ids);
			pager.setCount(items.size());
		} else if ("nearby".equals(queryType)) {
			String latlng = params.getFirst("latlng");
			if (StringUtils.contains(latlng, ",")) {
				String[] coords = latlng.split(",", 2);
				String rad = params.containsKey("radius") ? params.getFirst("radius") : null;
				int radius = NumberUtils.toInt(rad, 10);
				double lat = NumberUtils.toDouble(coords[0], 0);
				double lng = NumberUtils.toDouble(coords[1], 0);
				items = search.findNearby(appid, type, query, radius, lat, lng, pager);
			}
		} else if ("prefix".equals(queryType)) {
			items = search.findPrefix(appid, type, params.getFirst("field"), params.getFirst("prefix"), pager);
		} else if ("similar".equals(queryType)) {
			List<String> fields = params.get("fields");
			if (fields != null) {
				items = search.findSimilar(appid, type, params.getFirst("filterid"),
						fields.toArray(new String[]{}), params.getFirst("like"), pager);
			}
		} else if ("tagged".equals(queryType)) {
			List<String> tags = params.get("tags");
			if (tags != null) {
				items = search.findTagged(appid, type, tags.toArray(new String[]{}), pager);
			}
		} else if ("in".equals(queryType)) {
			items = search.findTermInList(appid, type, params.getFirst("field"), params.get("terms"), pager);
		} else if ("terms".equals(queryType)) {
			String matchAll = params.containsKey("matchall") ? params.getFirst("matchall") : "true";
			List<String> termsList = params.get("terms");
			if (termsList != null) {
				Map<String, String> terms = new HashMap<String, String>(termsList.size());
				for (String termTuple : termsList) {
					if (!StringUtils.isBlank(termTuple) && termTuple.contains(Config.SEPARATOR)) {
						String[] split = termTuple.split(Config.SEPARATOR, 2);
						terms.put(split[0], split[1]);
					}
				}
				if (params.containsKey("count")) {
					pager.setCount(search.getCount(appid, type, terms));
				} else {
					items = search.findTerms(appid, type, terms, Boolean.parseBoolean(matchAll), pager);
				}
			}
		} else if ("wildcard".equals(queryType)) {
			items = search.findWildcard(appid, type, params.getFirst("field"), query, pager);
		} else if ("count".equals(queryType)) {
			pager.setCount(search.getCount(appid, type));
		} else {
			items = search.findQuery(appid, type, query, pager);
		}

		result.put("items", items);
		result.put("totalHits", pager.getCount());
		return result;
	}

}
