package com.example.security.dpop.request;

import com.example.security.dpop.DPoPProofBuilder;
import com.nimbusds.jose.JOSEException;
import java.net.URI;
import java.util.Collections;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.security.oauth2.client.endpoint.AbstractOAuth2AuthorizationGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

public class DPoPOClientCredentialsGrantRequestEntityConverter
    implements Converter<DPoPClientCredentialsGrantRequest, RequestEntity<?>> {

  private final Converter<AbstractOAuth2AuthorizationGrantRequest, MultiValueMap<String, String>>
      parametersConverter;
  private final DPoPProofBuilder dPoPProofBuilder;
  private final RestOperations restOperations;

  public DPoPOClientCredentialsGrantRequestEntityConverter(
      Converter<AbstractOAuth2AuthorizationGrantRequest, MultiValueMap<String, String>>
          parametersConverter,
      DPoPProofBuilder dPoPProofBuilder) {
    this.parametersConverter = parametersConverter;
    this.dPoPProofBuilder = dPoPProofBuilder;
    this.restOperations = new RestTemplateBuilder().build();
  }

  private MultiValueMap<String, String> getParameters(
      DPoPClientCredentialsGrantRequest clientCredentialsGrantRequest,
      ClientRegistration clientRegistration) {
    MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
    parameters.add(
        OAuth2ParameterNames.GRANT_TYPE, clientCredentialsGrantRequest.getGrantType().getValue());
    if (!CollectionUtils.isEmpty(clientRegistration.getScopes())) {
      parameters.add(
          OAuth2ParameterNames.SCOPE,
          StringUtils.collectionToDelimitedString(clientRegistration.getScopes(), " "));
    }
    return parameters;
  }

  private URI getUri(DPoPClientCredentialsGrantRequest clientCredentialsGrantRequest) {
    return UriComponentsBuilder.fromUriString(
            clientCredentialsGrantRequest
                .getClientRegistration()
                .getProviderDetails()
                .getTokenUri())
        .build()
        .toUri();
  }

  @Override
  public RequestEntity<?> convert(
      DPoPClientCredentialsGrantRequest dPoPClientCredentialsGrantRequest) {
    ClientRegistration clientRegistration =
        dPoPClientCredentialsGrantRequest.getClientRegistration();

    URI uri = getUri(dPoPClientCredentialsGrantRequest);

    MultiValueMap<String, String> parameters =
        getParameters(dPoPClientCredentialsGrantRequest, clientRegistration);
    MultiValueMap<String, String> convertedParameters =
        parametersConverter.convert(dPoPClientCredentialsGrantRequest);
    if (convertedParameters != null) {
      parameters.addAll(convertedParameters);
    }

    HttpHeaders httpHeaders = new HttpHeaders();
    httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON_UTF8));
    httpHeaders.setContentType(
        MediaType.valueOf(MediaType.APPLICATION_FORM_URLENCODED_VALUE + ";charset=UTF-8"));

    String dPoPProofWithNonce = buildDPoPProof(uri, parameters, clientRegistration);
    if (dPoPProofWithNonce != null) {
      httpHeaders.set("DPoP", dPoPProofWithNonce);
    }

    return new RequestEntity<>(parameters, httpHeaders, HttpMethod.POST, uri);
  }

  private String buildDPoPProof(
      URI uri, MultiValueMap<String, String> parameters, ClientRegistration clientRegistration) {

    try {

      String dPoPProofWithoutNonce =
          dPoPProofBuilder.createDPoPProof(
              HttpMethod.POST.name(), uri.toString(), null, clientRegistration);

      HttpHeaders httpHeaders = new HttpHeaders();
      httpHeaders.set("Content-Type", "application/x-www-form-urlencoded");
      httpHeaders.set("DPoP", dPoPProofWithoutNonce);

      HttpEntity<?> httpEntity = new HttpEntity<>(parameters, httpHeaders);

      restOperations.postForEntity(uri, httpEntity, String.class);
    } catch (HttpClientErrorException ex) {
      if (ex.getStatusCode() == HttpStatus.BAD_REQUEST
          && ex.getResponseHeaders() != null
          && ex.getResponseBodyAsString().contains("use_dpop_nonce")) {
        String nonce = ex.getResponseHeaders().getFirst("DPoP-Nonce");
        try {
          return dPoPProofBuilder.createDPoPProof(
              HttpMethod.POST.name(), uri.toString(), nonce, clientRegistration);
        } catch (JOSEException e) {
          throw new OAuth2AuthorizationException(new OAuth2Error("Failed to create DPoP proof"), e);
        }

      } else {
        throw new OAuth2AuthorizationException(
            new OAuth2Error("Failed to obtain nonce: " + ex.getResponseBodyAsString()));
      }
    } catch (JOSEException e) {
      throw new OAuth2AuthorizationException(new OAuth2Error("Failed to create DPoP proof"), e);
    }
    return null;
  }
}
