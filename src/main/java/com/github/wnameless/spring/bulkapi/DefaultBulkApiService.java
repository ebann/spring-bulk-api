/*
 *
 * Copyright 2016 Wei-Ming Wu
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package com.github.wnameless.spring.bulkapi;

import static org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import javax.servlet.http.HttpServletRequest;

import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.RequestEntity.BodyBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * 
 * {@link DefaultBulkApiService} id the default implementation of
 * {@link BulkApiService}.
 *
 */
public class DefaultBulkApiService implements BulkApiService {

  private final ApplicationContext appCtx;
  private final Environment env;

  private BulkApiValidator validator;

  /**
   * Creates a {@link DefaultBulkApiService}.
   * 
   * @param appCtx
   *          the Spring {@link ApplicationContext}
   */
  public DefaultBulkApiService(ApplicationContext appCtx) {
    this.appCtx = appCtx;
    env = appCtx.getEnvironment();
  }

  private BulkApiValidator validator() {
    if (validator == null) validator = new BulkApiValidator(appCtx);

    return validator;
  }

  @Override
  public BulkResponse bulk(BulkRequest req, HttpServletRequest servReq) {
    validateBulkRequest(req, servReq);

    List<BulkResult> results = new ArrayList<BulkResult>();
    RestTemplate template = new RestTemplate();
    for (BulkOperation op : req.getOperations()) {
      BodyBuilder bodyBuilder = RequestEntity.method(//
          httpMethod(op.getMethod()), computeUri(servReq, op));

      ResponseEntity<String> rawRes =
          template.exchange(requestEntity(bodyBuilder, op), String.class);

      if (!op.isSilent()) results.add(buldResult(rawRes));
    }

    return new BulkResponse(results);
  }

  private RequestEntity<MultiValueMap<String, Object>> requestEntity(
      BodyBuilder bodyBuilder, BulkOperation op) {
    for (Entry<String, String> header : op.getHeaders().entrySet()) {
      bodyBuilder.header(header.getKey(), header.getValue());
    }

    MultiValueMap<String, Object> params =
        new LinkedMultiValueMap<String, Object>();
    for (Entry<String, ?> param : op.getParams().entrySet()) {
      params.add(param.getKey(), param.getValue());
    }

    return bodyBuilder.body(params);
  }

  private URI computeUri(HttpServletRequest servReq, BulkOperation op) {
    String rawUrl = servReq.getRequestURL().toString();
    String rawUri = servReq.getRequestURI().toString();

    if (op.getUrl() == null || isBulkPath(op.getUrl())) {
      throw new BulkApiException(UNPROCESSABLE_ENTITY,
          "Invalid URL(" + rawUri + ") exists in this bulk request");
    }

    URI uri;
    try {
      String servletPath = rawUrl.substring(0, rawUrl.indexOf(rawUri));
      uri = new URI(servletPath + urlify(op.getUrl()));
    } catch (URISyntaxException e) {
      throw new BulkApiException(UNPROCESSABLE_ENTITY, "Invalid URL("
          + urlify(op.getUrl()) + ") exists in this bulk request");
    }

    if (!validator().validatePath(urlify(op.getUrl()),
        httpMethod(op.getMethod()))) {
      throw new BulkApiException(UNPROCESSABLE_ENTITY, "Invalid URL("
          + urlify(op.getUrl()) + ") exists in this bulk request");
    }

    return uri;
  }

  private boolean isBulkPath(String url) {
    String bulkPath = urlify(env.getProperty("spring.bulk.api.path", "/bulk"));
    url = urlify(url);

    return url.equals(bulkPath) || url.startsWith(bulkPath + "/");
  }

  private String urlify(String url) {
    url = url.trim();
    return url.startsWith("/") ? url : "/" + url;
  }

  private BulkResult buldResult(ResponseEntity<String> rawRes) {
    BulkResult res = new BulkResult();
    res.setStatus(Short.valueOf(rawRes.getStatusCode().toString()));
    res.setHeaders(rawRes.getHeaders().toSingleValueMap());
    res.setBody(rawRes.getBody());

    return res;
  }

  private void validateBulkRequest(BulkRequest req,
      HttpServletRequest servReq) {
    int max = env.getProperty("spring.bulk.api.limit", int.class, 100);
    if (req.getOperations().size() > max) {
      throw new BulkApiException(PAYLOAD_TOO_LARGE,
          "Bulk operations exceed the limitation(" + max + ")");
    }

    // Check if any invalid URL exists
    for (BulkOperation op : req.getOperations()) {
      computeUri(servReq, op);
    }
  }

  private static HttpMethod httpMethod(String method) {
    try {
      return HttpMethod.valueOf(method.toUpperCase());
    } catch (Exception e) {
      return HttpMethod.GET;
    }
  }

}
